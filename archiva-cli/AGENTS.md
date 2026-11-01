# Archiva CLI – Agent Guide

## What this component does
`archiva-cli` builds a standalone Java command‑line client for Archiva. It wraps the core libraries and provides a convenient way to interact with an Archiva server from a terminal.

## Key entry points
- **Bazel target(s)** – `//archiva-cli:archiva-cli`
- **Main class** – `org.apache.archiva.cli.ArchivaCli` (or similar entry point depending on the version).
- **Executable** – After building, the binary can be run via `bazel run //archiva-cli:archiva-cli -- <args>`.

## Common tasks
| Task | Bazel command | Notes |
|------|---------------|-------|
| Build CLI binary | `bazel build //archiva-cli:archiva-cli` | Produces a jar under `bazel-bin/...` |
| Run CLI locally | `bazel run //archiva-cli:archiva-cli -- <options>` | Pass command‑line arguments after `--` |
| Test CLI | `bazel test //archiva-cli:...` | Runs unit tests for the CLI module |

## Frequently‑asked “gotchas”
- **Classpath dependencies** – The CLI depends on core Archiva libraries; ensure the `BUILD.bazel` declares the correct `deps`.
- **Configuration file location** – The CLI looks for a default `archiva.properties` on the classpath; you can override it with `-Darchiva.configuration=<path>`.

## Helpful Goose extensions
- **developer** – build and run the CLI with `shell`, edit source files, and inspect the generated jar.
- **analyze** – `analyze({"path":"archiva-cli"})` to view source structure.
- **extensionmanager** – enable `web-search` for external CLI documentation.

## Quick reference for future prompts
- **Add a new CLI command**: Create a new class under `src/main/java/...`, update the `BUILD.bazel` if needed, and register the command in the CLI argument parser.
- **Update CLI dependencies**: Edit the module’s `BUILD.bazel` `deps` list and run `bazel build //archiva-cli:archiva-cli`.
- **Run CLI against a remote Archiva server**: Provide `-Darchiva.url=<server-url>` as a JVM system property or via the CLI’s own option flags.

---
*Keep this file up‑to‑date as the code evolves. The content is intended for both humans and LLM agents.*