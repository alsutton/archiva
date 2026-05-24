# Vendored: Redback (subset)

archiva's master branch expects Redback 3.0-SNAPSHOT (sibling project,
unpublished). Redback 2.6.2 is on Maven Central and most of its API matches,
so MODULE.bazel pulls 15 redback-* artifacts from there.

The exception is `redback-rbac-model`, where the published 2.6.2 has
`UserAssignment.{get,set}RoleNames` / `addRoleName`, but the archiva master
sources use `RoleIds` / `addRoleId`. The two APIs are incompatible at compile
time. Vendoring just this one module (17 classes, slf4j-only) from the
upstream master keeps everything else on the released artifacts.

Source: https://github.com/apache/archiva-redback-core/tree/master/redback-rbac/redback-rbac-model
