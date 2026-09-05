"""Render XDocs XML source files to HTML at build time.

Used by archiva-docs/BUILD.bazel to turn `src/site/xdoc/**/*.xml` into
HTML files (alongside the markdown-rendered HTML above) so the same
document set can be published from a single staging rule instead of
from a site-plugin-driven workflow.

The XDoc conversion emits a flat HTML file alongside the converted
HTML file that is placed at the same location (e.g. `site/xdoc/index.xml`
→ `site/xdoc/index.html`). This is the same layout Maven produced at
site/staging/ after running `mvn site`.

"""

load("//tools/bazel:markdown_site.bzl", "markdown_to_html")
load("//tools/bazel:staged_files.bzl", "staged_files")

def _safe_name(s):
    """Sanitise a string for use as a Bazel target name."""
    out = ""
    for ch in s.elems():
        if ch.isalnum() or ch == "_":
            out += ch
        else:
            out += "_"
    return out

def xdoc_to_html(name, srcs, strip_prefix, renderer, visibility = None):
    """Convert `srcs` XDoc XML files to HTML.

    For each `<strip_prefix>/<rel>.xml` in `srcs`, emits a genrule that
    produces `<rel>.html` (i.e. the strip_prefix is dropped from the
    output path, so consumers can place html and resources/ side by side
    at the doc-site root).

    Args:
        name: filegroup target name to collect all rendered outputs.
        srcs: list of XDoc XML source labels (workspace-relative paths).
        strip_prefix: package-relative prefix to strip from each src to
            compute the output path.
        renderer: label of the xdoc_to_html.py script.
        visibility: filegroup visibility.
    """
    outputs = []
    for src in srcs:
        if not src.endswith(".xml"):
            fail("xdoc_to_html: expected .xml file, got %s" % src)
        if not src.startswith(strip_prefix + "/"):
            fail("xdoc_to_html: src %s is not under strip_prefix %s" % (src, strip_prefix))
        rel = src[len(strip_prefix) + 1:]
        out = rel[:-len(".xml")] + ".html"
        outputs.append(out)
        native.genrule(
            name = "_xdoc_" + name + "_" + _safe_name(rel),
            srcs = [src, renderer],
            outs = [out],
            cmd = "python3 $(location {renderer}) $(location {src}) $@ {rel}".format(
                renderer = renderer,
                src = src,
                rel = out,
            ),
        )

    native.filegroup(
        name = name,
        srcs = outputs,
        visibility = visibility or ["//visibility:public"],
    )
