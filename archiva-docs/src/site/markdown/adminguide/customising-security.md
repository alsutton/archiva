# Archiva Security Configuration

Security properties and password rules are configured now in the Redback Runtime Configuration
properties (see [Redback Runtime Configuration](./redback-runtime-configuration.html#Runtime_properties)).

The Redback Runtime Configuration properties are stored in `archiva.xml`.
The former `security.properties` file, if it exists, is only used once for populating the
Runtime Configuration settings. After that, this file will be ignored.

These are the default properties. The file can be found in in Redback's svn repo:
[config-defaults.properties](http://svn.apache.org/repos/asf/archiva/redback/redback-core/trunk/redback-configuration/src/main/resources/org/apache/archiva/redback/config-defaults.properties)

## %{snippet|ignoreDownloadError=true|url=https://raw.githubusercontent.com/apache/archiva-redback-core/master/redback-configuration/src/main/resources/org/apache/archiva/redback/config-defaults.properties}

**Note:** If installed standalone, Archiva's list of configuration files is *itself* configurable, and
can be found in:
`apps/archiva/WEB-INF/applicationContext.xml`

Values from sources

## %{snippet|id=configuration-files-list|ignoreDownloadError=true|url=https://raw.githubusercontent.com/apache/archiva/master/archiva-modules/archiva-web/archiva-webapp/src/main/webapp/WEB-INF/applicationContext.xml}
