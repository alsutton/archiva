Apache Archiva
==============

Licensing information
=====================

Archiva is developed under the Apache License Version 2.0 

Please notice, the download distribution includes third party Java libraries that are not covered by Apache license, namely:
- Common Development and Distribution License (CDDL)
- Mozilla License
- Day Specification License


Archiva Development
===================

To get involved in Archiva development, contact dev@archiva.apache.org.

Archiva builds with [Bazel](https://bazel.build). See [BAZEL.md](BAZEL.md) for
a full overview of the build, including module layout, the `archiva_module()`
macro, and how source-level divergences from upstream are handled.

Bazel 9.1.0 is pinned in `.bazelversion`; install
[bazelisk](https://github.com/bazelbuild/bazelisk) so it picks up automatically.

Building from Source Code
=========================

```shell
# Build everything
bazel build //...

# Run the tests
bazel test //...

# Build the runnable artifacts
bazel build //archiva-cli:archiva-cli                            # java_binary
bazel build //archiva-modules/archiva-web/archiva-webapp:archiva # archiva.war
bazel build //archiva-jetty:archiva-jetty-dist                   # tar.gz distribution
```

Running from Source Code
========================

Build and unpack the Jetty distribution, then launch it with the bundled
script:

```shell
bazel build //archiva-jetty:archiva-jetty-dist
mkdir -p /tmp/archiva-run && tar -xzf bazel-bin/archiva-jetty/archiva-jetty-dist.tar.gz -C /tmp/archiva-run
/tmp/archiva-run/archiva/bin/archiva
```

For convenience the repo ships a wrapper that runs the same steps:
`sh ./jetty.sh` (debug with `sh ./jetty-debug.sh`, debug port is 8000).

hit your browser: http://localhost:9091/archiva/index.html

Test Registration email
========================
Redback can send email on registration. By default the mail jndi is configured to use localhost.
You can use your gmail account for testing purpose.

Drop a tomcat context file at a location of your choosing with the contents
below, and point the running container at it (e.g. via the `archiva-jetty`
configuration under `archiva-jetty/src/main/conf/`).

```xml
<Context path="/archiva">
  <Resource name="jdbc/users" auth="Container" type="javax.sql.DataSource"
            username="sa"
            password=""
            driverClassName="org.apache.derby.jdbc.EmbeddedDriver"
            url="jdbc:derby:${catalina.base}/target/database/users;create=true"
  />
  <Resource name="mail/Session" auth="Container"
          type="javax.mail.Session"
          mail.smtp.host="smtp.gmail.com"
          mail.smtp.port="465"
          mail.smtp.auth="true"
          mail.smtp.user="your gmail account"
          password="your gmail password"
          mail.smtp.starttls.enable="true"
          mail.smtp.socketFactory.class="javax.net.ssl.SSLSocketFactory"/>

</Context>
```

Using with cassandra as metadata storage
========================
You can run the application using cassandra as storage. The bundled
`archiva-cassandra.properties` lives in `archiva-jetty/src/main/conf/` and is
included in the distribution; enable it by passing the relevant system
properties when launching:

```shell
sh ./jetty.sh -Dcassandra.host=localhost -Dcassandra.port=9160
```

Default cassandra host is localhost and port 9160. You can override using:

 * `-Dcassandra.host=`
 * `-Dcassandra.port=`
