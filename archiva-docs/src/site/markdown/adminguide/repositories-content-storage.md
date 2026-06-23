# Repositories Content Storage

## Repositories Metadata Content Storage

With version 2.0.2, Metadata repository content can also be stored in an Apache Cassandra database.

It's possible to switch implementation with configuring the system property (-Darchiva.repositorySessionFactory.id=) with one
of the id below.

There are now 3 implementations of storage:

- File (id: file)

- Jackarabbit (default one) (id: jcr)

- Cassandra (id: cassandra)

## Jackrabbit

Prior to version 1.4-M1, repository content is now stored in a jcr repository (based on Apache Jackrabbit implementation).

A default Jackrabbit configuration is provided :

## %{snippet|id=default-repository|ignoreDownloadError=true|url=https://raw.githubusercontent.com/apache/archiva/master/archiva-modules/plugins/metadata-store-jcr/src/main/resources/org/apache/archiva/metadata/repository/jcr/repository.xml}

You can use your own configuration by adding a file repository.xml in ${appserver.base}/conf.

By default, the Jcr repository is stored ${appserver.base}/data/jcr.

If you want to change this default location, you must edit the file WEB-INF/applicationContext.xml, uncomment/edit lines and change with your values:

## %{snippet|id=jcr-location|ignoreDownloadError=true|url=https://raw.githubusercontent.com/apache/archiva/master/archiva-modules/archiva-web/archiva-webapp/src/main/webapp/WEB-INF/applicationContext.xml}

## Cassandra

With the distribution including the embeded Jetty, you can configure Cassandra runtime in the file conf/archiva-cassandra.properties.

The default content:

```
cassandra.host=localhost
cassandra.port=9160
cassandra.maxActive=20
cassandra.readConsistencyLevel=QUORUM
cassandra.writeConsistencyLevel=QUORUM
cassandra.replicationFactor=1
cassandra.keyspace.name=ArchivaKeySpace
cassandra.cluster.name=archiva
```

If you deploy in a Web Application container, you can configure a path to this configuration file using the system property: -Darchiva.cassandra.configuration.file=
