# Archiva Components (vendored) – Agent Guide

## What this component does
`third_party/archiva-components` holds a collection of third‑party libraries that Archiva vendors because they are either not available on Maven Central, require custom shading, or need a specific version that diverges from the upstream release.

## Key libraries included
- **spring‑cache** – Cache abstraction used by Archiva modules.
- **spring‑registry** – Service‑registry utilities.
- **spring‑quartz** – Scheduler integration.
- **spring‑taskqueue** – Async task queue.
- **rest‑util** – Helper classes for REST communication.
- **expression‑evaluator** – Simple expression language used in configuration.

## Common tasks
| Task | Bazel command | Notes |
|------|---------------|-------|
| Build vendored libs | `bazel build //third_party/archiva-components/...` | |
| Update a vendored library | Replace the source under this directory and run `bazel fetch //...` to refresh hashes. |

## Frequently‑asked “gotchas”
- **Version drift** – Since these libs are vendored, they do not receive automatic security updates. When a new upstream release appears, you must manually copy the sources and update the `BUILD.bazel` files.
- **Shade conflicts** – Some Archiva modules (e.g., Maven proxy) shade parts of these libraries. Changing a vendored library may require updating the shade plugin configuration.

## Helpful Goose extensions
- **developer** – explore the vendored code, run `rg` searches, and edit files.
- **extensionmanager** – enable `web-search` to look up upstream project documentation.

## Quick reference for future prompts
- **Upgrade a vendored component**: Replace the folder contents, update any `BUILD.bazel` `srcs` lists, bump the SHA in `MODULE.bazel` if referenced, then run `bazel fetch //...`.
- **Remove an unused vendored lib**: Delete its directory and any references in `BUILD.bazel` files, then run `bazel test //...` to ensure nothing breaks.

---
*Keep this file up‑to‑date as the code evolves. The content is intended for both humans and LLM agents.*