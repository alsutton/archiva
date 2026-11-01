# Archiva Plugins – Agent Guide

## What this component does
The **plugins** package contains optional extensions that add extra functionality to Archiva, such as audit logging, generic metadata support, problem reports, repository statistics, and repository‑merge staging.

## Key entry points
- **Bazel target(s)** – `//archiva-modules/plugins/...`
- **Main classes** – each plugin follows the `org.apache.archiva.plugin` package hierarchy (e.g., `org.apache.archiva.plugin.audit.AuditPlugin`).
- **CLI / script** – Plugins are loaded by the Archiva server at runtime; they are not executable on their own.

## Common tasks
| Task | Bazel command | Notes |
|------|---------------|-------|
| Build all plugins | `bazel build //archiva-modules/plugins/...` | |
| Test a specific plugin | `bazel test //archiva-modules/plugins/audit:...` | |
| Add a new plugin | Create a new sub‑directory, add a `BUILD.bazel` with `archiva_module(name="<plugin-name>")`, and implement the plugin SPI.

## Frequently‑asked “gotchas”
- **Classloading** – Plugins are loaded via a custom classloader; ensure any third‑party dependencies are declared in the plugin's `BUILD.bazel`.
- **Configuration** – Plugin-specific properties are read from `archiva.properties` under the `plugin.<name>.` prefix.

## Helpful Goose extensions
- **developer** – run Bazel commands, edit Java files, and explore the directory.
- **analyze** – `analyze({"path":"archiva-modules/plugins"})` for a quick overview.
- **extensionmanager** – enable `web-search` for external plugin documentation.

## Quick reference for future prompts
- **Create a new plugin**: Follow the existing plugin layout, implement `org.apache.archiva.plugin.Plugin` interface, and register it in `META-INF/services/org.apache.archiva.plugin.Plugin`.
- **Update plugin dependencies**: Edit the plugin's `BUILD.bazel` and run `bazel fetch //...`.
- **Enable/disable plugins**: Edit `archiva-jetty/src/main/conf/archiva.properties` to set `plugin.<name>.enabled=true|false`.

---
*Keep this file up‑to‑date as the code evolves. The content is intended for both humans and LLM agents.*