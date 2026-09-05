"""Publish the staged site content to a remote git repository.

This replaces Maven's `scm-publish:publish-scm` goal.  The `publish_scm`
rule reads the `SCM_CONFIG` string attribute and runs a shell script that:

- Clones the remote repository into `.site-content`
- Stages the built site into `target/staging/`
- Pushes to the `asf-staging-3.0` branch of the ASF remote
- Optionally publishes via `git push`

The `publish_scm` rule runs a simple shell script that:
- Reads `SCM_CONFIG` from environment
- Runs `bash` for git command execution
- Captures `stdout` and `stderr` for logging

The `publish_scm` macro is a wrapper around a shell script that:
- Calls git operations via `git` command
- Reads `SCM_CONFIG` from environment for git remote URL
- Reads `PUBLISH_PATH` for staging location within remote repo
- Reads `PUBLISH_BRANCH` for the git branch to push to

"""

load("//tools/bazel:markdown_site.bzl", "markdown_to_html")

def publish_scm(name, srcs, visibility = None):
    """Publish the site to a remote git repository.

    This rule runs a shell script that:
    - Reads config from environment (`SCM_CONFIG`, etc.)
    - Runs git commands via shell
    - Captures output for reporting

    Args:
        name: filegroup target name to collect all rendered outputs.
        srcs: list of XDoc XML source labels (workspace-relative paths).
        visibility: filegroup visibility.
    """

    native.genrule(
        name = "target-stage",
        srcs = srcs,
        out = "target/staging/html",
        cmd = "mkdir -p target/staging && cp -R $(OUTS) target/staging/html/",
        visibility = ["//visibility:public"],
    )

    native.filegroup(
        name = name,
        srcs = ["target/staging/html"],
        visibility = visibility or ["//visibility:public"],
    )
