# Writing a Consumer Plugin

For a sample custom consumer, you can checkout the archiva-consumer-plugin at the archiva sandbox in the SVN repository:
Starting with release 1.4, plexus components are not anymore supported, you must use Spring components.

## Writing manually

Below are the steps on how to create a custom repository consumer and plug it in Archiva:

1. Create a project for your component.

2. Declare your class or in this case, consumer as a component as shown in the example below. This
should be put at the class level.

```
Ex.
@Service("knownRepositoryContentConsumer#discover-new-artifact")
@Scope("prototype")

where,
  Service: unique id for your consumer.
  Scope: the creation strategy prototype or singleton.
```

4. Package your project by executing 'mvn clean package'

5. Let's say you are using the apache-archiva-${project.version}-bin.tar.gz to run Archiva. Unpack
the binaries then go to bin/linux-x86-32/ (assuming you are running on Linux), then execute
'./run.sh console'. Then stop or shutdown Archiva after it started. (This is necessary to unpack
the war file.)

6. Copy the jar file you created in step 4 in apache-archiva-${project.version}/apps/archiva/webapp/lib/

7. Add the necessary configurations in archiva.xml (in this case, add 'discover-new-artifact' as a
*knownContentConsumer*)

8. Start up Archiva again.

## Use the archetype

Prior to version 1.4-M2, you can use an archetype.

```
mvn archetype:generate \
   -DarchetypeRepository=repo1.maven.org \
   -DarchetypeGroupId=org.apache.archiva \
   -DarchetypeArtifactId=archiva-consumer-archetype \
   -DarchetypeVersion=${project.version}
```

Note: if you want to use a SNAPSHOT version, replace with the following parameter: -DarchetypeRepository=https://archiva-repository.apache.org/archiva/repository/snapshots/ \
