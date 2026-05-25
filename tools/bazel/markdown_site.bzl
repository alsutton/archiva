"""Render a tree of Markdown source files to HTML at build time.

Used by archiva-docs/BUILD.bazel to turn src/site/markdown/**.md into a
parallel tree of .html files via tools/bazel/md_to_html.py. The macro
emits one genrule per markdown file (so Bazel can rebuild only what
changed) plus a filegroup that gathers the html outputs.

Avoiding rules_python keeps the module graph small; the renderer is
stdlib-only Python invoked via the host `python3`. The same assumption
already applies elsewhere in this repo (the staged_files genrules rely
on host shell utilities).
"""

def _safe_name(s):
    out = ""
    for ch in s.elems():
        if ch.isalnum() or ch == "_":
            out += ch
        else:
            out += "_"
    return out

def markdown_to_html(name, srcs, strip_prefix, renderer, visibility = None):
    """Render `srcs` Markdown files to HTML.

    For each `<strip_prefix>/<rel>.md` in `srcs`, emits a genrule that
    produces `<rel>.html` (i.e. the strip_prefix is dropped from the
    output path, so consumers can place html and `resources/` side by
    side at the doc-site root).

    Args:
      name: filegroup target name to collect all rendered outputs.
      srcs: list of Markdown source labels (workspace-relative paths).
      strip_prefix: package-relative prefix to strip from each src to
        compute the output path.
      renderer: label of the md_to_html.py script.
      visibility: filegroup visibility.
    """
    outputs = []
    for src in srcs:
        if not src.endswith(".md"):
            fail("markdown_to_html: expected .md file, got %s" % src)
        if not src.startswith(strip_prefix + "/"):
            fail("markdown_to_html: src %s is not under strip_prefix %s" % (src, strip_prefix))
        rel = src[len(strip_prefix) + 1:]
        out = rel[:-len(".md")] + ".html"
        outputs.append(out)
        native.genrule(
            name = "_md_" + name + "_" + _safe_name(rel),
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
