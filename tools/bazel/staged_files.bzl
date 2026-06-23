"""staged_files: place source files at custom output paths.

Some archiva tests read from hardcoded paths like
`target/test-repository/com/example/foo.jar`. Maven populates those via
maven-dependency-plugin:unpack-dependencies. We replicate by emitting
one genrule per src and gathering the outputs into a filegroup. When the
filegroup is added to a test's `data`, Bazel symlinks each output into
the test's runfiles tree at the workspace-relative output path -- so
running the test from the runfiles root makes
`Paths.get("target/test-repository/com/example/foo.jar")` resolve.

Caveats:
- `data` on java_test does NOT propagate runfiles symlinks/root_symlinks
  from a custom rule's DefaultInfo (Bazel 9.1 behaviour I observed
  empirically). Only declared file outputs propagate. Hence many small
  genrules instead of `ctx.runfiles(symlinks=...)` or one declare_file-
  based shell action.
- Place the macro call in the *workspace-root* BUILD so output paths
  appear at `<runfiles>/<workspace>/<dest>` rather than under a
  sub-package.
- Bazel macros can't introspect a filegroup, so callers pass the source
  paths as plain strings alongside the filegroup label. The two have to
  line up: every entry in `src_paths` must be a file in
  `srcs_filegroup`.
"""

def _safe_name(s):
    out = ""
    for ch in s.elems():
        if ch.isalnum() or ch == "_":
            out += ch
        else:
            out += "_"
    return out

def staged_files(name, srcs_filegroup, src_paths, strip_prefix, prefix, visibility = None):
    """Stage each src at <prefix>/<src-rel-to-strip-prefix>.

    Args:
      name: filegroup target name to expose to consumers.
      srcs_filegroup: label whose default outputs are the source files
        (e.g. `//archiva-modules/metadata/test-repository:raw-resources`).
      src_paths: workspace-relative path strings for each source file.
      strip_prefix: workspace-relative prefix stripped from each src_path.
      prefix: prepended to the stripped path to compute the output.
      visibility: filegroup visibility.
    """
    outputs = []
    for src_path in src_paths:
        if not src_path.startswith(strip_prefix + "/"):
            fail("src_path %s does not start with strip_prefix %s" % (src_path, strip_prefix))
        rel = src_path[len(strip_prefix) + 1:]
        out = (prefix + "/" + rel) if prefix else rel
        outputs.append(out)
        native.genrule(
            name = "_stage_" + name + "_" + _safe_name(rel),
            srcs = [srcs_filegroup],
            outs = [out],
            # Scan the (potentially many) srcs for the one whose tail matches
            # the src path. cp it. This is N^2 per genrule but each stage is
            # small.
            cmd = "for f in $(SRCS); do case \"$$f\" in *" + src_path + ") cp \"$$f\" $@; exit 0;; esac; done; echo 'stage: src not found: " + src_path + "' >&2; exit 1",
        )
    native.filegroup(
        name = name,
        srcs = outputs,
        visibility = visibility or ["//visibility:public"],
    )
