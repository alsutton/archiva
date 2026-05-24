# Building Archiva with Bazel

This repo has both a Maven build (`pom.xml`) and a Bazel build
(`MODULE.bazel` + `BUILD.bazel` files). Maven is still authoritative
for releases; Bazel runs in parallel for faster iterative builds.

## Quick start

```sh
# Build everything
bazel build //...

# Run the tests
bazel test //...

# Build the runnable artifacts
bazel build //archiva-cli:archiva-cli                       # java_binary
bazel build //archiva-modules/archiva-web/archiva-webapp:archiva  # archiva.war
bazel build //archiva-jetty:archiva-jetty-dist              # tar.gz distribution
```

Bazel 9.1.0 is pinned in `.bazelversion`. Use bazelisk to pick it up
automatically (`brew install bazelisk` or grab the binary from the
[releases page](https://github.com/bazelbuild/bazelisk/releases)).

## Layout

| Path                                            | Purpose                                            |
| ----------------------------------------------- | -------------------------------------------------- |
| `MODULE.bazel`                                  | Bzlmod: rules deps + Maven artifact pins           |
| `.bazelversion`, `.bazelrc`                     | Bazel version + JVM toolchain config               |
| `BUILD.bazel`                                   | Workspace-root target staging (`target-test-repository`) |
| `tools/bazel/archiva.bzl`                       | `archiva_module()` macro for per-module BUILDs     |
| `tools/bazel/war.bzl`                           | `java_war()` rule for the WAR                      |
| `tools/bazel/staged_files.bzl`                  | Path-renaming rule for Maven-layout test fixtures  |
| `tools/bazel/src/.../ArchivaTestClassLoader.java` | Custom system CL papering over jar-URI tests     |
| `third_party/archiva-components/`               | Vendored archiva-components master (not on Maven Central) |
| `third_party/redback/redback-rbac-model/`       | Vendored Redback master rbac-model (API drift)     |

## Adding a new module

Most archiva modules call `archiva_module()` from `tools/bazel/archiva.bzl`:

```python
load("//tools/bazel:archiva.bzl", "archiva_module")

archiva_module(
    name = "my-module",
    deps = [
        "//archiva-modules/archiva-base/archiva-common",
        "@maven//:org_apache_commons_commons_lang3",
    ],
    test_deps = [...],          # additional compile deps for tests
    runtime_test_deps = [...],  # log4j and Spring runtime impls
    # test_framework = "jupiter", # for JUnit 5; default is JUnit 4
    # skip_tests = True,          # if tests need infra we don't have
    # manual_tests = {...},       # tag specific tests with ["manual"]
    # test_data = [...],          # extra runfiles deps (e.g. staging)
    # test_jvm_flags = [...],     # extra -D flags for surefire compatibility
)
```

The macro encodes Archiva's Maven conventions: `src/main/java/**`,
`src/main/resources/**` packaged into the jar, `src/test/java/**` into
a sibling `test-lib` target, `src/test/resources/**` both on the test
classpath and exposed as runfiles, `-Dbasedir=<package>` set for tests
that call `FileUtils.getBasedir()`. The custom test classloader is
hooked in automatically. `Abstract*Test.java` is filtered out of the
test glob (those are bases without `@Test` methods).

For non-archiva (third-party vendored) modules use a plain `java_library`.

## What's wired vs not

55+ Java modules build cleanly. Three deployables: `archiva-cli` (a
runnable launcher), `archiva.war` (~60 MB), and the
`archiva-jetty-dist.tar.gz` Jetty distribution (~62 MB) that bundles
the WAR with jetty-runner and a shell launcher.

Around 14 tests are tagged `manual` — see the `manual_tests = {...}`
dicts in module BUILD files for the per-test reason. Three recurring
patterns account for most of them:

1. **JUnit Jupiter mixed in JUnit 4 modules.** A non-public Jupiter
   test class can't run via Bazel's bundled JUnit 4 runner. Moving the
   whole module to `test_framework = "jupiter"` would fix it but
   reshuffles the rest of the module's tests too.
2. **Hardcoded `target/<dir>/` paths Maven creates on demand.** Some
   tests write into `target/test-repository-target/` or similar; the
   sandbox doesn't pre-create those.
3. **Oak Lucene shading.** `scheduler-repository` integration tests
   boot Apache Oak, which bundles Lucene 4.7. Maven's `oak-jcr-lucene`
   module runs maven-shade-plugin to relocate Oak's Lucene to a
   private package so it doesn't clash with newer Lucene on the
   classpath. We don't have a Bazel jarjar/ASM rule for this; the
   alternative is a single bytecode-rewriting rule that would also
   help with other shaded jars (e.g. maven-indexer's bundled Lucene
   is solved by pinning the `:shaded-lucene` Maven classifier
   instead).

## Source-level divergences from upstream Archiva

Archiva master expects Redback 3.0-SNAPSHOT (a sibling git project
that isn't published to Maven Central). The Bazel build pulls Redback
**2.6.2** from Maven Central — the latest release. The two API
surfaces differ in a few specific places, fixed with targeted source
patches:

| Where                                  | Patch                                                                 |
| -------------------------------------- | --------------------------------------------------------------------- |
| Multiple `@Named("commons-configuration")` | Renamed bean to `archiva-commons-configuration` (vendored archiva-components' `@Service` updated to match). Avoids a Spring `ConflictingBeanDefinitionException` with Redback 2.6.2's identically-named legacy registry class. |
| `archiva-rest-services` `catch (NamingException e)` | Broadened to `catch (Exception e)` (5 sites). `ldapConnection.close()` throws `NamingException` in Redback 3.x but not in 2.6.2, and javac rejects unthrown checked-exception catches. |
| `ArchivaLockedAdminEnvironmentCheck`'s `RedbackRoleConstants.SYSTEM_ADMINISTRATOR_ROLE_ID` | Renamed to `SYSTEM_ADMINISTRATOR_ROLE` (the 2.6.2 constant lacks the `_ID` suffix). |
| `ArchivaLdapRoleMapperConfiguration.getLdapGroupMapping(String)` `@Override` | Removed (the 2.6.2 interface doesn't declare the method; it stays as a plain helper). |

These are reversible — when Redback 3.x is published, the targeted
patches go away and the bean name reverts.

The other shape of divergence is **vendored upstream subsets** under
`third_party/`:

- `third_party/archiva-components/` — entire stack (spring-cache,
  spring-registry, spring-quartz, spring-taskqueue, rest-util,
  expression-evaluator) cloned verbatim from
  `apache/archiva-components` master. The upstream repo was archived
  2024-04-25 with no published releases.
- `third_party/redback/redback-rbac-model/` — single module vendored
  from `apache/archiva-redback-core` master because archiva uses the
  3.x `UserAssignment.{set,get,add}RoleId(s)` API while 2.6.2 uses the
  `RoleName(s)` API. Vendoring just this one module bridges the gap;
  everything else stays on the released 2.6.2 artifacts.

## CI

`.github/workflows/bazel.yml` runs `bazel build //...` and `bazel test
//...` on every push and PR alongside the existing Maven workflow.
Caches `~/.cache/bazel` and `~/.cache/bazelisk` keyed on
`MODULE.bazel`, `.bazelversion`, and the Starlark in `tools/bazel`,
so source-only changes hit the cache.

## How to refresh the staged test-repository fixture

If the `archiva-modules/metadata/test-repository/src/main/resources/`
tree gains or loses files, regenerate the explicit path list:

```sh
{
  echo 'TEST_REPOSITORY_FILES = ['
  find archiva-modules/metadata/test-repository/src/main/resources \
    -type f | sort | sed 's/^/    "/; s|$|",|'
  echo ']'
} > archiva-modules/metadata/test-repository/files.bzl
```

The `staged_files` macro reads this list to emit a genrule per fixture
file. The list is explicit because Starlark macros can't introspect a
filegroup's contents at load time.
