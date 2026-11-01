# Archiva Maven Integration – Agent Guide

## What this component does
The **archiva-maven** package provides Maven‑specific utilities, models, and proxy/indexer services that enable Archiva to act as a Maven repository manager.

## Key entry points
- **Bazel target(s)** – `//archiva-modules/archiva-maven/...`
- **Main classes** – e.g. `org.apache.archiva.maven.indexer.ArchivaMavenIndexer`, `org.apache.archiva.maven.proxy.ArchivaMavenProxy`.
- **CLI / script** – Accessed via the Archiva server; individual modules are not directly executable.

## Common tasks
| Task | Bazel command | Notes |
|------|---------------|-------|
| Build Maven modules | `bazel build //archiva-modules/archiva-maven/...` | |
| Test a specific Maven module | `bazel test //archiva-modules/archiva-maven/archiva-maven-common:...` | |
| Run integration test for the Maven proxy | `bazel test //archiva-modules/archiva-maven/archiva-maven-proxy:...` | |

## Frequently‑asked “gotchas”
- **Shade plugin conflicts** – The Maven proxy and indexer modules use Maven's shade plugin to relocate Lucene and other libraries. Changing versions may require updating the shade configuration.
- **Version mismatches** – Ensure the Maven version used by Archiva (declared in `MODULE.bazel`) matches the version expected by the Maven‑related tests.

## Helpful Goose extensions
- **developer** – run Bazel commands, edit Java files, search with `rg`.
- **analyze** – `analyze({"path":"archiva-modules/archiva-maven"})` for a quick overview.
- **extensionmanager** – enable `web-search` for external Maven documentation.

## Quick reference for future prompts
- **Add a new Maven‑specific feature**: create a new sub‑module under `archiva-modules/archiva-maven/`, add a `BUILD.bazel` with `archiva_module(name="<name>")`, and declare the necessary dependencies.
- **Update Maven‑related third‑party libs**: edit `MODULE.bazel` and run `bazel fetch //...`.
- **Run Archiva with Maven proxy**: configure `archiva.properties` to point to the Maven proxy endpoint.

---
*Keep this file up‑to‑date as the code evolves. The content is intended for both humans and LLM agents.*