# Archiva Jetty Distribution – Agent Guide

## What this component does
`archiva-jetty` builds the runnable Jetty distribution that packages Archiva as a standalone server. It contains the launch scripts, the generated WAR, and the default configuration files.

## Key entry points
- **Bazel target(s)** – `//archiva-jetty:archiva-jetty-dist`
- **Scripts** – `jetty.sh` (standard launch), `jetty-debug.sh` (debug port 8000), and the generated `archiva/bin/archiva` wrapper.
- **Configuration** – `archiva-jetty/src/main/conf/archiva.properties` (default server properties) and optional `archiva-cassandra.properties` for Cassandra metadata storage.

## Common tasks
| Task | Bazel command | Notes |
|------|---------------|-------|
| Build Jetty distribution | `bazel build //archiva-jetty:archiva-jetty-dist` | |
| Run locally | `sh ./jetty.sh` (or `sh ./jetty-debug.sh` for remote debugging) | |
| Test Jetty packaging | `bazel test //archiva-jetty:...` | |

## Frequently‑asked “gotchas”
- **Configuration overrides** – You can point Archiva to a custom properties file via `-Darchiva.configuration=<path>` when launching the script.
- **Cassandra storage** – To enable Cassandra metadata, set `-Dcassandra.host` and `-Dcassandra.port` system properties when invoking `jetty.sh`.

## Helpful Goose extensions
- **developer** – run the launch scripts, edit configuration files, and rebuild the distribution.
- **analyze** – `analyze({"path":"archiva-jetty"})` for a quick overview of the scripts and config files.
- **extensionmanager** – enable `web-search` for Jetty documentation.

## Quick reference for future prompts
- **Customize server port**: Edit `archiva.properties` (property `jetty.port`) or pass `-Djetty.port=9092` when launching.
- **Add JVM options**: Set `ARCHIVA_OPTS` environment variable or modify the wrapper script to include extra `-Xmx` flags.
- **Run Archiva with a custom config**: Provide `-Darchiva.configuration=<your‑conf>.properties` to the launch script.

---
*Keep this file up‑to‑date as the code evolves. The content is intended for both humans and LLM agents.*