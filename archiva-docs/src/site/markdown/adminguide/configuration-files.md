# Configuration Files of Apache Archiva

While Archiva is primarily configured via the graphical administration interface, it stores all configuration in XML configuration
files that can be hand edited and used for backup and migration.

The following files compose the configuration for Archiva:

- `archiva.xml` - this is the primary Archiva configuration file

- `security.properties` - This file, if it exists, is only read once to populate the
Redback Runtime Configuration properties (see [Redback Runtime Configuration](./redback-runtime-configuration.html#Runtime_Properties),
stored in `archiva.xml`. The file will be ignored after this.

This section will focus on the `archiva.xml` file.

## The Archiva configuration file

The Archiva configuration file is stored in one of two locations:

- The application server configuration directory (see [installing Archiva standalone](./standalone.html) for more information)

- The user home directory (`~/.m2/archiva.xml`).

When modified in the GUI, the file is written back to the location it was initially read from, with the home directory taking priority if both exist. When using a
standalone installation, it is highly recommended that a configuration file is only maintained in one of the locations.

For a complete reference of the configuration file see: [Reference](http://archiva.apache.org/ref/0/archiva-base/archiva-configuration/configuration.html)

The following shows a basic configuration file:

```
<configuration>
  <version>2</version>
  <managedRepositories>
    <managedRepository>
      <location>${appserver.base}/repositories/internal</location>
      <retentionPeriod>30</retentionPeriod>
      <id>internal</id>
      <name>Archiva Managed Internal Repository</name>
    </managedRepository>
  </managedRepositories>
  <remoteRepositories>
    <remoteRepository>
      <url>http://repo1.maven.org/maven2</url>
      <id>central</id>
      <name>Central Repository</name>
    </remoteRepository>
  </remoteRepositories>
  <proxyConnectors>
    <proxyConnector>
      <sourceRepoId>internal</sourceRepoId>
      <targetRepoId>central</targetRepoId>
      <policies>
        <releases>always</releases>
        <checksum>fix</checksum>
        <snapshots>never</snapshots>
        <cache-failures>no</cache-failures>
      </policies>
    </proxyConnector>
  </proxyConnectors>
</configuration>
```
