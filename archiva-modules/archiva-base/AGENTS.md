# Archiva Base Modules – Agent Guide

## What this component does
The **archiva-base** package contains the core infrastructure of Archiva – configuration, model, repository APIs, security utilities, and other foundational services used by the rest of the system.

## Key entry points
- **Bazel target(s)** – `//archiva-modules/archiva-base/...` (all sub‑modules)
- **Main classes** – e.g. `org.apache.archiva.configuration.ArchivaConfiguration`, `org.apache.archiva.model.ArchivaRepository`.
- **CLI / script** – Not directly executable; accessed via the Archiva server or other modules.

## Common tasks
| Task | Bazel command | Notes |
|------|---------------|-------|
| Build all base modules | `bazel build //archiva-modules/archiva-base/...` | |
| Test a specific sub‑module (e.g., archiva-common) | `bazel test //archiva-modules/archiva-base/archiva-common:...` | |
| Run a unit test class | `bazel test //archiva-modules/archiva-base/archiva-common --test_arg=--tests=MyTestClass` | |

## Frequently‑asked “gotchas”
- **JUnit version mixing** – Some sub‑modules contain JUnit‑Jupiter tests but are executed with the JUnit‑4 runner. Editing those tests may require updating the runner configuration.
- **Hard‑coded `target/` paths** – Tests that write to `target/…` will fail under Bazel sandbox unless the directory is created beforehand.

## Helpful Goose extensions
- **developer** – run Bazel commands, edit Java files, and explore the directory tree.
- **analyze** – `analyze({"path":"archiva-modules/archiva-base"})` gives a quick overview of file counts and function signatures.
- **extensionmanager** – enable `web-search` if you need external documentation.

## Quick reference for future prompts
- **Locate a class**: `analyze({"path":"archiva-modules/archiva-base/archiva-common","focus":"ClassName"})`
- **Add a new sub‑module**: create a directory, add a `BUILD.bazel` with `archiva_module(name="<name>")`, then add the module to the parent `BUILD.bazel` if needed.
- **Update a third‑party dependency**: edit `MODULE.bazel`, run `bazel fetch //...`, then re‑run `bazel test //...`.
- **Run Archiva with a custom config**: edit `archiva-jetty/src/main/conf/archiva.properties` or pass `-Darchiva.configuration=<path>`.

---
*Keep this file up‑to‑date as the code evolves. The content is intended for both humans and LLM agents.*