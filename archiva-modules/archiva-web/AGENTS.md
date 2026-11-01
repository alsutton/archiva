# Archiva Web Services – Agent Guide

## What this component does
The **archiva-web** package provides the HTTP/REST UI, WebDAV support, and related web utilities that power Archiva's web console and API.

## Key entry points
- **Bazel target(s)** – `//archiva-modules/archiva-web/...`
- **Main classes** – e.g. `org.apache.archiva.webapp.ArchivaWebApp`, `org.apache.archiva.rest.ArchivaRestService`.
- **CLI / script** – Launched via the Jetty distribution (`archiva-jetty`).

## Common tasks
| Task | Bazel command | Notes |
|------|---------------|-------|
| Build web modules | `bazel build //archiva-modules/archiva-web/...` | |
| Test a specific web module | `bazel test //archiva-modules/archiva-web/archiva-webapp:...` | |
| Run integration test for REST services | `bazel test //archiva-modules/archiva-web/archiva-rest:...` | |

## Frequently‑asked “gotchas”
- **Servlet configuration** – The WAR relies on `archiva-webapp/src/main/webapp/WEB-INF/web.xml`. Changing container settings often requires updating that file.
- **Static resource paths** – Tests that refer to `src/main/webapp` must use the correct relative path when run under Bazel sandbox.

## Helpful Goose extensions
- **developer** – run Bazel commands, edit Java/JSP files, and explore the directory.
- **analyze** – `analyze({"path":"archiva-modules/archiva-web"})` for a quick overview.
- **extensionmanager** – enable `web-search` for external web‑framework docs.

## Quick reference for future prompts
- **Add a new REST endpoint**: create a class under `archiva-modules/archiva-web/archiva-rest`, add the appropriate JAX‑RS annotations, and update the `web.xml` bindings.
- **Update third‑party web libraries**: edit `MODULE.bazel` and run `bazel fetch //...`.
- **Run Archiva with a custom web config**: edit `archiva-jetty/src/main/conf/archiva.properties` or supply a custom `jetty.xml`.

---
*Keep this file up‑to‑date as the code evolves. The content is intended for both humans and LLM agents.*