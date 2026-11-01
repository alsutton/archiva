"""Starlark rule that verifies every directory containing a BUILD.bazel file also contains an AGENTS.md file.

If any such directory is missing the AGENTS.md file, the rule fails during workspace loading,
causing the build to stop with a clear error message.
"""

def _agents_check_impl(ctx):
    # Use a shell command to locate all BUILD.bazel files and verify the sibling AGENTS.md.
    # The command prints the first missing file and exits with non‑zero status.
    cmd = [
        "bash",
        "-c",
        "set -e; find . -name BUILD.bazel -exec dirname {} \\; | while read d; do if [[ ! -f \"$d/AGENTS.md\" ]]; then echo \"Missing AGENTS.md in $d\"; exit 1; fi; done",
    ]
    result = ctx.execute(cmd)
    if result.return_code != 0:
        fail(result.stdout + result.stderr)
    return []

agents_check = repository_rule(
    implementation = _agents_check_impl,
    attrs = {},
)
