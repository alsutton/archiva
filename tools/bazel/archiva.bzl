"""Conventions for Archiva modules under Bazel.

`archiva_module` is the macro every module under archiva-modules/ should call.
It mirrors the implicit conventions a Maven build assumes (src/main/java,
src/test/java, src/test/resources, basedir-relative file access, log4j on the
test runtime classpath) so a converted module's BUILD file stays small.
"""

load("@rules_java//java:defs.bzl", "java_library", "java_test")

# Runtime deps every test gets unless explicitly disabled. Maven inherits
# these from the archiva-base parent pom's test-scope dependencies.
_DEFAULT_TEST_RUNTIME_DEPS = [
    "@maven//:org_apache_logging_log4j_log4j_slf4j_impl",
    "@maven//:org_apache_logging_log4j_log4j_core",
]

# Extra runtime deps when test_framework="jupiter". Includes both engines so
# JUnit 4-style tests in the same module (via vintage) still run.
_JUPITER_TEST_RUNTIME_DEPS = [
    "@maven//:org_junit_jupiter_junit_jupiter_engine",
    "@maven//:org_junit_platform_junit_platform_console_standalone",
]

def _class_name(test_src):
    return test_src.replace("src/test/java/", "").replace(".java", "").replace("/", ".")

def archiva_module(
        name,
        deps = [],
        test_deps = [],
        runtime_test_deps = None,
        test_framework = "junit4",
        manual_tests = None,
        test_jvm_flags = None,
        skip_tests = False,
        test_size = "small",
        visibility = None):
    """Defines a Maven-shaped Archiva module.

    Produces:
      - `:{name}` java_library from src/main/java/**/*.java
      - `:test-lib` java_library (testonly) from src/test/java with src/test/resources
        wired in as both classpath resources and runfiles data
      - one java_test per concrete `src/test/java/**/*Test.java` (Abstract*
        is filtered out), with -Dbasedir pointing at the module's
        workspace-relative path.

    Args:
      name: module name; also the java_library label.
      deps: compile deps for src/main/java.
      test_deps: additional compile deps for src/test/java. junit + the main
        library are added automatically.
      runtime_test_deps: replaces the default log4j runtime deps if set.
      test_framework: "junit4" (default) uses Bazel's built-in JUnit4 runner.
        "jupiter" switches to the JUnit Platform console launcher so JUnit 5
        tests run correctly (Bazel's built-in runner only understands JUnit 4).
      manual_tests: dict mapping a test source path (e.g.
        "src/test/java/.../FooTest.java") to a list of tags. Used to exclude
        tests from `//...` runs that don't translate cleanly from Maven.
      test_size: bazel test size attribute for all generated tests.
      visibility: visibility for the main library. Defaults to public so other
        archiva modules can depend on it.
    """
    if visibility == None:
        visibility = ["//visibility:public"]
    if runtime_test_deps == None:
        runtime_test_deps = list(_DEFAULT_TEST_RUNTIME_DEPS)
        if test_framework == "jupiter":
            runtime_test_deps += _JUPITER_TEST_RUNTIME_DEPS
    if manual_tests == None:
        manual_tests = {}

    main_srcs = native.glob(["src/main/java/**/*.java"], allow_empty = True)
    main_resources = native.glob(["src/main/resources/**"], allow_empty = True)
    test_srcs = native.glob(["src/test/java/**/*.java"], allow_empty = True)
    test_resources = native.glob(["src/test/resources/**"], allow_empty = True)
    # Anything under src/test besides java/ and resources/ — e.g. src/test/examples,
    # src/test/conf, src/test/repository — is exposed as runfiles so tests that
    # resolve files via basedir-relative paths can find them.
    test_data = native.glob(
        ["src/test/**"],
        exclude = ["src/test/java/**", "src/test/resources/**"],
        allow_empty = True,
    )

    if main_srcs:
        java_library(
            name = name,
            srcs = main_srcs,
            resources = main_resources,
            resource_strip_prefix = "%s/src/main/resources" % native.package_name() if main_resources else None,
            visibility = visibility,
            deps = deps,
        )

    if not test_srcs or skip_tests:
        return

    native.filegroup(
        name = "test-resources",
        srcs = test_resources + test_data,
    )

    test_lib_deps = ["@maven//:junit_junit"]
    if main_srcs:
        # Pulling in the main library re-exports its transitive deps to tests.
        test_lib_deps.append(":" + name)
    else:
        # No main library — tests need the module's compile deps directly.
        test_lib_deps += deps
    # Dedupe so callers can list junit/etc. explicitly without conflict.
    for d in test_deps:
        if d not in test_lib_deps:
            test_lib_deps.append(d)

    java_library(
        name = "test-lib",
        srcs = test_srcs,
        # Equivalent to Maven's test-jar attachment: other modules' tests can
        # depend on this module's test sources (e.g. abstract test bases).
        visibility = ["//visibility:public"],
        resources = test_resources,
        resource_strip_prefix = "%s/src/test/resources" % native.package_name(),
        testonly = True,
        deps = test_lib_deps,
    )

    # `-Dbasedir` covers tests that use FileUtils.getBasedir(). Tests that
    # bypass that helper and call `Paths.get("src/test/...")` directly will
    # need to be tagged `manual` (see manual_tests) — setting `-Duser.dir`
    # breaks Bazel's test runner bootstrap, so there's no clean global fix.
    common_jvm_flags = [
        "-Dbasedir=%s" % native.package_name(),
    ]
    if test_jvm_flags:
        common_jvm_flags = common_jvm_flags + test_jvm_flags

    # Filter abstract bases — files named Abstract*.java define shared @Before
    # / helpers but have no @Test methods, so the JUnit runner rejects them.
    concrete_tests = [
        t
        for t in native.glob(["src/test/java/**/*Test.java"], allow_empty = True)
        if not t.split("/")[-1].startswith("Abstract")
    ]

    for test_src in concrete_tests:
        test_kwargs = dict(
            name = _class_name(test_src),
            size = test_size,
            data = [":test-resources"],
            jvm_flags = common_jvm_flags,
            tags = manual_tests.get(test_src, []),
            runtime_deps = [":test-lib"] + runtime_test_deps,
        )
        if test_framework == "jupiter":
            # JUnit 5 / Jupiter requires the platform launcher; Bazel's bundled
            # runner only understands JUnit 4. The console-launcher main accepts
            # --select-class and reports via the platform engine SPI.
            test_kwargs["use_testrunner"] = False
            test_kwargs["main_class"] = "org.junit.platform.console.ConsoleLauncher"
            test_kwargs["args"] = ["--select-class=" + _class_name(test_src), "--disable-banner", "--details=tree"]
        else:
            test_kwargs["test_class"] = _class_name(test_src)
        java_test(**test_kwargs)
