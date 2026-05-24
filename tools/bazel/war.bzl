load("@rules_java//java/common:java_info.bzl", "JavaInfo")

"""Custom rule that bundles a Java web application as a WAR.

`java_war(name, deps, webapp_root, web_xml)` produces a `<name>.war` zip
containing:
  - everything under `webapp_root` at the WAR root (`/index.html`, etc.)
  - `web_xml` and any other WEB-INF files mapped to `/WEB-INF/...`
  - every transitive runtime jar of `deps` in `/WEB-INF/lib/<basename>`

The runtime jars come from JavaInfo.transitive_runtime_jars, which is what
distinguishes this from a plain pkg_zip rule. WAR servers (Jetty, Tomcat)
expect every dependency on `WEB-INF/lib/` -- a deploy/fat jar wouldn't
deploy correctly.
"""

def _java_war_impl(ctx):
    war = ctx.actions.declare_file(ctx.label.name + ".war")

    runtime_jars = depset(transitive = [
        d[JavaInfo].transitive_runtime_jars
        for d in ctx.attr.deps
        if JavaInfo in d
    ])

    # Map source files to their position inside the WAR.
    placements = []

    # Webapp content at the WAR root. The strip_prefix is the path
    # underneath the package that we want to strip; whatever's beneath
    # becomes the WAR-internal path.
    strip = ctx.attr.webapp_root
    for f in ctx.files.webapp_files:
        # short_path is workspace-relative; strip the package path then the
        # webapp_root prefix.
        short = f.short_path
        pkg_prefix = ctx.label.package + "/"
        if short.startswith(pkg_prefix):
            short = short[len(pkg_prefix):]
        if short.startswith(strip + "/"):
            short = short[len(strip) + 1:]
        placements.append((f, short))

    # Runtime jars under WEB-INF/lib.
    for jar in runtime_jars.to_list():
        placements.append((jar, "WEB-INF/lib/" + jar.basename))

    # Build a manifest file the zipper script will read.
    manifest_lines = [src.path + "\t" + dest for (src, dest) in placements]
    manifest = ctx.actions.declare_file(ctx.label.name + ".manifest")
    ctx.actions.write(manifest, "\n".join(manifest_lines))

    inputs = [manifest] + [src for (src, _) in placements]
    # `zip` isn't on the sandbox PATH but python3 is. zipfile preserves the
    # tab-separated `src<TAB>dest` manifest layout.
    ctx.actions.run_shell(
        outputs = [war],
        inputs = inputs,
        progress_message = "Assembling WAR " + war.short_path,
        command = """python3 - <<'PY'
import os, zipfile
with zipfile.ZipFile("{war}", "w", zipfile.ZIP_DEFLATED) as z:
    with open("{manifest}") as m:
        for line in m:
            line = line.rstrip("\\n")
            if not line:
                continue
            src, dest = line.split("\\t", 1)
            z.write(src, arcname=dest)
PY
""".format(
            manifest = manifest.path,
            war = war.path,
        ),
    )
    return [DefaultInfo(files = depset([war]))]

java_war = rule(
    implementation = _java_war_impl,
    attrs = {
        "deps": attr.label_list(
            providers = [[JavaInfo]],
            doc = "Runtime classpath. Each dep's transitive_runtime_jars " +
                  "is dropped into WEB-INF/lib/.",
        ),
        "webapp_files": attr.label_list(
            allow_files = True,
            doc = "Static resources whose paths under webapp_root become " +
                  "the WAR-internal layout.",
        ),
        "webapp_root": attr.string(
            doc = "Path under the package (e.g. 'src/main/webapp') that " +
                  "should be stripped off webapp_files' paths.",
        ),
    },
)
