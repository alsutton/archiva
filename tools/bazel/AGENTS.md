# Bazel Build Helpers – Agent Guide

## What this component does
The **tools/bazel** directory contains Starlark macros and rules that the Archiva build relies on, including the custom `archiva_module()` macro, `java_war()` rule, and helpers for staged test files.

## Key entry points
- **Macros / rules** – `archiva.bzl` (defines `archiva_module()`), `war.bzl` (defines `java_war()`), `staged_files.bzl`.
- **Utility scripts** – `apt_to_md.py`, `md_to_html.py`, `xdoc_to_html.py` used by the documentation generation pipeline.
- **BUILD.bazel** – the top‑level BUILD for the tools package.

## Common tasks
| Task | Bazel command | Notes |
|------|---------------|-------|
| Build a tool module | `bazel build //tools/bazel:<module-name>` | |
| Test a Starlark rule (if tests exist) | `bazel test //tools/bazel:...` | |
| Regenerate documentation files | Run the appropriate python script, e.g. `python3 tools/bazel/md_to_html.py <src> <dest>` |

## Frequently‑asked “gotchas”
- **Macro versioning** – When adding a new Archiva module you must use the `archiva_module()` macro; forgetting to include required attributes (e.g., `srcs`, `deps`) will cause a build error.
- **Staged files** – The `staged_files.bzl` rule expects a specific directory layout; altering it without updating the rule can break test‑repository fixtures.

## Helpful Goose extensions
- **developer** – run `shell` to invoke Bazel, edit Starlark files, and run Python helpers.
- **analyze** – `analyze({"path":"tools/bazel"})` for a quick overview of the Starlark code.
- **extensionmanager** – enable `web-search` if you need external Bazel documentation.

## Quick reference for future prompts
- **Add a new sub‑module**: Create a directory under `archiva-modules/...`, add a `BUILD.bazel` that calls `archiva_module(name="<module>")`, and list the new target in the parent `BUILD.bazel` if required.
- **Update a third‑party dependency**: Edit `MODULE.bazel` (Bzlmod), then run `bazel fetch //...`.
- **Run Archiva with a custom config**: Edit `archiva-jetty/src/main/conf/archiva.properties` or supply `-Darchiva.configuration=<path>` when launching.

---
*Keep this file up‑to‑date as the code evolves. The content is intended for both humans and LLM agents.*