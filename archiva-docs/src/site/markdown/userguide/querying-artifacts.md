# Querying Artifacts

Sometimes you only need to download artifacts using a query and via the command line

## Query format

```
  wget "http://localhost:9091/archiva/restServices/archivaServices/searchService/artifact?g=org.apache.archiva&a=archiva-model&v=LATEST"
```

Query parameters:

- g= groupId (mandatory)

- a= artifactId (mandatory)

- v= the version (or LATEST keyword) (mandatory)

- r= the repository (optional, if none all repositories available for reading for the current user are searched)

**NOTE**: the response is a redirect (so you need to follow redirect if you want to download the artifact). No content
response is returned if the query doesn't match any artifact.
