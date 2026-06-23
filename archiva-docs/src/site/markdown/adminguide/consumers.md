# Understanding Consumers in Apache Archiva

Archiva makes use of the concept of consumers. Consumers are components which consumes or processes an artifact. There is on type
of consumers in Archiva: repository content consumers.

## Repository Content Consumers

The repository content consumers consume or process artifacts during repository scanning. For every artifact found in the repository,
each consumer processes them. There are 8 available repository content consumers which can be configured in the Repository Scanning
page. These are:

- **auto-remove** - Removes files in the repository being scanned if the file type matches any of the configured file types to be removed.

- **auto-rename** - Automatically renames common artifact mistakes.

- **create-missing-checksums** - Creates the md5 and sha1 checksum files of the artifact if there are none available in the repository.

- **index-content** - Adds the content of the artifact (specifically the pom) to the index, allowing the artifact to be searched in Archiva.

- **metadata-updater** - Updates artifact metadata files depending on the content of the repository.

- **repository-purge** - Removes old snapshots from the repository either by the number of days old or by the retention count.

- **validate-checksums** - Validates the checksum files of the artifact.

- **create-archiva-metadata** - Take an artifact off of disk and put it into the metadata repository.

- **duplicate-artifacts** - Search the artifact repository of known SHA1 Checksums for potential duplicate artifacts.

![consumers](../images/consumers.png)
