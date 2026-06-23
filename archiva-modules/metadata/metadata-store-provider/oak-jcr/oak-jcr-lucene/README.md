oak-jcr-lucene
==============

Historically this module repackaged the `oak-lucene` dependency, relocating
its Apache Lucene 4 classes into `shaded_oak.org.apache.lucene` so that newer
Lucene releases on Archiva's classpath would not clash with Jackrabbit Oak's
bundled (and effectively end-of-life) Lucene 4.

Under the Maven build that relocation was done with `maven-shade-plugin`, and
`oak-lucene` itself was excluded from the pom since it is a fat jar that
already includes the Lucene classes.

The Bazel build does not currently perform the shading. There is no
ruleset wired up for jarjar / ASM-based bytecode rewriting in this repo, so
the module produces no jar of its own under Bazel. See the "What's wired vs
not" section of the top-level [BAZEL.md](../../../../../BAZEL.md) for the
broader context (item 3, "Oak Lucene shading"). Modules that need the
shaded classes are currently tagged `manual` on the Bazel side.
