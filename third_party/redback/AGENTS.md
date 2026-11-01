# Redback Compatibility – Agent Guide

## What this component does
`third_party/redback/redback-rbac-model` contains a vendored copy of the Redback RBAC model that Archiva uses. The copy is required because Archiva depends on API methods (`UserAssignment.set/get/addRoleId(s)`) that are only present in Redback 3.x, while the released Redback 2.6.2 version uses `RoleName(s)`.

## Key entry points
- **Main interfaces** – `org.apache.redback.rbac.UserAssignment`, `org.apache.redback.rbac.Role`.
- **Usage** – Referenced by Archiva security and permission modules in `archiva-modules/archiva-base`.

## Common tasks
| Task | Bazel command | Notes |
|------|---------------|-------|
| Build vendored Redback code | `bazel build //third_party/redback/...` | |
| Update vendored Redback copy | Replace the source under this directory, adjust `BUILD.bazel` if needed, and run `bazel fetch //...` |

## Frequently‑asked “gotchas”
- **API mismatch** – Archiva’s code expects the newer RBAC API; any attempt to upgrade to the released Redback 2.6.2 without the vendored copy will cause compilation errors.
- **Patch locations** – The repository contains small patches that adapt the Redback 3.x API to the Archiva code base; be careful not to delete them.

## Helpful Goose extensions
- **developer** – explore the vendored code, run `rg` to locate RBAC usages, and edit files.
- **extensionmanager** – enable `web-search` to look up Redback upstream documentation.

## Quick reference for future prompts
- **Upgrade Redback**: Identify the exact version needed, replace the source, update any `BUILD.bazel` `srcs`, and verify with `bazel test //...`.
- **Remove vendored Redback**: Delete the directory and ensure no Archiva module still imports its classes; then run the full test suite.

---
*Keep this file up‑to‑date as the code evolves. The content is intended for both humans and LLM agents.*