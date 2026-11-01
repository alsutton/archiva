# Archiva Metadata Modules – Agent Guide

## What this component does
The **metadata** package defines the model and API for Archiva’s internal metadata (repository statistics, artifact information, and usage data). It provides interfaces that other modules implement to store and query metadata.

## Key entry points
- **Bazel target(s)** – `//archiva-modules/metadata/...`
- **Main interfaces** – e.g. `org.apache.archiva.metadata.model.MetadataRepository`, `org.apache.archiva.metadata.repository.MetadataRepositoryFactory`.
- **Test fixtures** – `archiva-modules/metadata/test-repository` contains generated Bzl files that model a sample repository used in integration tests.

## Common tasks
| Task | Bazel command | Notes |
|------|---------------|-------|
| Build metadata modules | `bazel build //archiva-modules/metadata/...` | |
| Test a specific metadata module | `bazel test //archiva-modules/metadata/metadata-model:...` | |
| Regenerate test‑repository fixture | See `BAZEL.md` for the find/sed one‑liner; then run `bazel test //archiva-modules/metadata/test-repository:...` |

## Frequently‑asked “gotchas”
- **Fixture regeneration** – The Bzl file `files.bzl` must be regenerated when the underlying test data changes; otherwise tests may fail.
- **Version‑sensitive APIs** – Some metadata APIs depend on specific versions of Redback; keep the version in sync with `MODULE.bazel`.

## Helpful Goose extensions
- **developer** – run Bazel commands, edit Java files, and explore the directory.
- **analyze** – `analyze({"path":"archiva-modules/metadata"})` for a quick overview.
- **extensionmanager** – enable `web-search` for external metadata design docs.

## Quick reference for future prompts
- **Add a new metadata type**: Create a new sub‑module, add the model classes, and expose them via a new factory interface.
- **Update third‑party metadata dependencies**: Edit `MODULE.bazel` and run `bazel fetch //...`.
- **Run Archiva with custom metadata storage**: configure the appropriate properties in `archiva-jetty/src/main/conf/archiva.properties`.

---
*Keep this file up‑to‑date as the code evolves. The content is intended for both humans and LLM agents.*