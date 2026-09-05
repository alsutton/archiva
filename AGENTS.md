# Apache Archiva Project

## Overview
Apache Archiva is an extensible build artifact repository management tool for Maven, Continuum, and ANT. It supports remote repository proxying, security access management, build artifact storage, delivery, browsing, indexing, usage reporting, extensible scanning, and more.
- **Homepage**: https://archiva.apache.org
- **Latest Release**: 2.2.7 (Dec 2021)
- **Language**: Java
- **Category**: build-management (Apache PMC)
- **Issues**: https://issues.apache.org/jira/browse/MRM
- **Mailing List**: https://archiva.apache.org/mailing-lists.html
- **Developers**: dev@archiva.apache.org

## License
Apache License Version 2.0. Distribute includes third-party libraries under CDDL, Mozilla License, and BSD Specification License.

## Build System
- **Build Tool**: Bazel (pin Bazel 9.1.0 via `.bazelversion`; use bazelisk)
- **Bzlmod Modules**: `MODULE.bazel` (rules deps + Maven artifact pins)
- **Layout**: `BUILD.bazel` at workspace root, per-module BUILDs use `archiva_module()` macro from `tools/bazel/archiva.bzl`
- **Deployables**: `archiva-cli` (java binary), `archiva.war` (~60MB), `archiva-jetty-dist.tar.gz` (~62MB Jetty tarball)

### Quick Build Commands
- Build everything: `bazel build //...`
- Run tests: `bazel test //...`
- CLI binary: `bazel build //archiva-cli:archiva-cli`
- WAR: `bazel build //archiva-modules/archiva-web/archiva-webapp:archiva`
- Jetty dist: `bazel build //archiva-jetty:archiva-jetty-dist`

## Running from Source
1. Build the Jetty distribution: `bazel build //archiva-jetty:archiva-jetty-dist`
2. Unpack: `mkdir -p /tmp/archiva-run && tar -xzf bazel-out/archiva-jetty/archiva-jetty-dist.tar.gz -C /tmp/archiva-run`
3. Launch: `/tmp/archiva-run/archiva/bin/archiva`
4. Browser: http://localhost:9091/archiva/index.html
- Or run the wrapper: `sh ./jetty.sh` (or `sh ./jetty-debug.sh` for debug port 8000)

## Source Modules (archiva-modules/)
### archiva-base/ — Core Infrastructure (~25 sub-modules)
- `archiva-common` — Common utilities
- `archiva-configuration` — Configuration layer
- `archiva-security-common` — Security common utilities
- `archiva-model` — Domain models
- `archiva-storage-api/fS` — Storage APIs (file-based)
- `archiva-proxy-api/proxy` — Proxy APIs and implementation
- `archiva-repository-api/scanner/layer` — Repository APIs and scanner/layer
- `archiva-event-api/central` — Event APIs
- `archiva-filelock` — File locking
- `archiva-policies` — Repository policies
- `archiva-test-utils` — Test utilities
- Plus additional modules: `archiva-plexus-bridge`, `archiva-mock`, `archiva-xml-tools`, `archiva-transaction`, `archiva-consumers`, `archiva-repository-admin`, `archiva-checksum`, `archiva-storage-api/storage-fs`

### archiva-scheduler/ — Scanning & Repository Scheduling (~6 sub-modules)
- `archiva-scheduler-api` — Scheduler API
- `archiva-scheduler-repository-api` — Repository-specific scheduler API
- `archiva-scheduler-indexing` — Indexing scheduler tasks
- `archiva-scheduler-repository` — Repository scheduler tasks, plus source scanning

### archiva-maven/ — Maven Integration (~8 sub-modules)
- `archiva-maven-common`, `archiva-maven-model`, `archiva-maven-metadata`, `archiva-maven-indexer`, `archiva-maven-proxy`, `archiva-maven-repository` — Maven-specific utilities and APIs
- `archiva-maven-scheduler` — Maven scheduler tasks

### archiva-web/ — Web Services (~10 sub-modules)
- `archiva-webapp`, `archiva-web-common` — WAR app and common web utilities
- `archiva-webdav` — WebDAV support
- `archiva-rest` — REST services
- `archiva-rss` — RSS feeds
- `archiva-security` — Web security
- `archiva-test-mocks` — Test mocks for web layer
- Plus: `archiva-webapp-test`

## Build Tools (tools/bazel/)
- `tools/bazel/archiva.bzl` — `archiva_module()` macro used by all archiva modules
- `tools/bazel/war.bzl` — `java_war()` rule
- `tools/bazel/staged_files.bzl` — Path-renaming rule for Maven-layout test fixtures
- `tools/bazel/src/.../ArchivaTestClassLoader.java` — Custom system classloader
- CI: `.github/workflows/bazel.yml` runs build + test on every push/PR

## Third-Party Vendoring (third_party/)
- `third_party/archiva-components/` — Vendored from `apache/archiva-components` master (archived 2024-04-25; no releases). Includes: spring-cache, spring-registry, spring-quartz, spring-taskqueue, rest-util, expression-evaluator.
- `third_party/redback/redback-rbac-model/` — Vendored from `apache/archiva-redback-core` master. Vendored only because archiva uses the 3.x `UserAssignment.{set,get,add}RoleId(s)` API while 2.6.2 (published) uses `RoleName(s)` API.

## Redback Compatibility Notes
- Archiva 2.2.7 builds with Redback 2.6.2 from Maven Central
- Redback 3.0-SNAPSHOT is upstream but API surfaces differ; use Redback 2.6.2 (latest release).
- Source patches exist for API differences between Redback 2.6.2 and 3.0-SNAPSHOT.

## Manual Tests (~14)
Three recurring patterns account for most manual tests:
1. JUnit Jupiter mixed in JUnit 4 modules — non-public Jupiter test can't run via Bazel's bundled JUnit 4 runner.
2. Hardcoded `target/<dir>/` paths Maven creates on demand — sandbox doesn't pre-create them.
3. Oak Lucene shading — `scheduler-repository` integration tests boot Apache Oak, which bundles Lucene 4.7; Maven's `oak-jcr-lucene` uses maven-shade-plugin to relocate Oak's Lucene to avoid clashes.

## Running as Cassandra Metadata Storage
`archiva-jetty/src/main/conf/archiva-cassandra.properties` — enable via system properties:
- `sh ./jetty.sh -Dcassandra.host=localhost -Dcassandra.port=9160`

## CI and Source Updates
- Bazel CI caches `~/.cache/bazel` and `~/.cache/bazelisk` keyed on `MODULE.bazel`, `.bazelversion`, and Starlark in `tools/bazel`.
- For test-repository fixture updates, regenerate `archiva-modules/metadata/test-repository/files.bzl` with the find/sed one-liner in BAZEL.md.
