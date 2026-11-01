# Archiva Scheduler – Agent Guide

## What this component does
The **archiva-scheduler** package coordinates repository scanning, indexing, and other scheduled background tasks that keep Archiva’s data up‑to‑date.

## Key entry points
- **Bazel target(s)** – `//archiva-modules/archiva-scheduler/...`
- **Main classes** – e.g. `org.apache.archiva.scheduler.api.Scheduler`, `org.apache.archiva.scheduler.repository.RepositoryScheduler`.
- **CLI / script** – Scheduler work is triggered by the Archiva server at runtime; you can also invoke tasks manually via Bazel.

## Common tasks
| Task | Bazel command | Notes |
|------|---------------|-------|
| Build scheduler modules | `bazel build //archiva-modules/archiva-scheduler/...` | |
| Test a specific scheduler module | `bazel test //archiva-modules/archiva-scheduler/archiva-scheduler-repository:...` | |
| Run a scheduler integration test | `bazel test //archiva-modules/archiva-scheduler/archiva-scheduler-repository:...` | |

## Frequently‑asked “gotchas”
- **Oak Lucene shading** – Integration tests that start Oak may fail if the shaded Lucene classes are not on the classpath.
- **Timing‑sensitive tests** – Scheduler tests sometimes rely on wall‑clock time; they may be flaky under heavy load.

## Helpful Goose extensions
- **developer** – run Bazel commands, edit Java files, and explore the directory.
- **analyze** – `analyze({"path":"archiva-modules/archiva-scheduler"})` for a quick overview.
- **extensionmanager** – enable `web-search` for external scheduler documentation.

## Quick reference for future prompts
- **Add a new scheduled task**: create a new sub‑module, add a `BUILD.bazel` with `archiva_module(name="<name>")`, implement the task class, and register it in the scheduler configuration.
- **Update scheduler dependencies**: edit `MODULE.bazel` and run `bazel fetch //...`.

---
*Keep this file up‑to‑date as the code evolves. The content is intended for both humans and LLM agents.*