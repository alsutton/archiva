# Vendored: archiva-redback-core

Subset of `apache/archiva-redback-core` (Apache 2.0, license headers in each
source file) vendored here for the same reasons as `archiva-components` next
door — the upstream repo has no published artifacts and archiva's parent pom
expects to pull SNAPSHOT builds from a sibling git project that Jenkins
bootstraps. Without vendoring, downstream modules that need anything from
`org.apache.archiva.redback.*` cannot build.

Vendored modules grow incrementally as downstream archiva modules need them.
Source: https://github.com/apache/archiva-redback-core (master).
