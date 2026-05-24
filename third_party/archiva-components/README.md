# Vendored: archiva-components

Subset of `apache/archiva-components` (Apache 2.0) vendored here because:

- The upstream repository was archived 2024-04-25 with no published releases.
- The artifacts (`org.apache.archiva.components.cache:*`) are not on Maven
  Central; the only way to get them otherwise is to build the sibling git
  project from source.
- Archiva's own `pom.xml` does not define `archiva.comp.version` — it is
  inherited from `archiva-parent:19-SNAPSHOT`, which is itself only
  buildable from a sibling git project.

Only the modules that `archiva-policies` (and downstream modules) actually
depend on are vendored:

- `spring-cache-api/` — the `Cache<K,V>`/`CacheStatistics` interfaces and
  abstract base classes.
- `spring-cache-ehcache/` — the `EhcacheCache` implementation referenced
  by `META-INF/spring-context.xml` in archiva-policies (production and
  test configs both reference this class by FQN).

Source: https://github.com/apache/archiva-components (master @ archive).
