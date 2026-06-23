# Upgrading Archiva

Upgrading Archiva is straightforward, particularly if the directions for separating the base from the installation
are followed. You need to retain the following directories:

- `conf` - the configuration files can be reused across different versions. Note that when the standalone
version is used, `wrapper.conf` and `jetty.xml` may need to be replaced after upgrade and changes
reapplied. See specific notes in the version details below.

- `data` - all Archiva data. More specifically:

- `data/repositories` is portable across versions (both forwards and backwards).

- `data/databases/users` must always be retained as it contains the permissions and user information across
versions, even if LDAP is being used for authentication.

- `data/databases/archiva` exists for Archiva 1.3.x and below. This can be retained through upgrades, but
can also be regenerated at any time. This no longer exists in Archiva 1.4+.

The following describes instructions specific to upgrading between different versions.

## Upgrading Archiva to 1.4+

### Metadata Repository

### Java Package Changes and Library Changes

All Java packages have changed from `org.apache.maven.archiva` to `org.apache.archiva` and have undergone
significant changes. If you have written custom consumers, are using the XML-RPC client, or interfacing with other
Java libraries the code will need to be adjusted.

### Configuration References

If you had used the undocumented ability to modify `application.xml` to alter the configuration search path, note
that in Archiva 1.4+ you must use the `org.apache.archiva` configuration search path instead of
`org.apache.maven.archiva`.

### Updated Jetty configuration

If you are using the Jetty standalone configuration with a custom
`jetty.xml`, note that it has been upgraded to Jetty 8 as of Archiva 1.4-M2.
You will need to recreate your customisations using the `jetty.xml` shipped
with Archiva.

In addition, you now need to ensure that a `temp` directory is created
inside the Archiva application base directory (alongside `conf`, `data`
and `logs`).

The default webapp context is now / (see [Standalone Distribution](./standalone.html))
