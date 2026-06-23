# Quick Start

## Installing Archiva

The quickest way to install Archiva is to [download](http://archiva.apache.org/download.html) and use the standalone distribution.
This includes a bundled Jetty server which can be easily started.
For more information on advanced configuration of this instance, refer to the [Administration Guide](./adminguide/standalone.html).

To get started right away, you can run the following after unpacking:

```
./bin/archiva console (Linux, Mac, Solaris)
.\bin\archiva.bat console (Windows)
```

You will need to choose a different start command based on your platform. The `console` argument
starts the server with the logs going to standard output, and waits for Ctrl+C to stop the server.

Archiva is now running on `<http://localhost:8080/>`

## Setting up your Archiva instance

You can now browse the web administration of Archiva. There will be a few basic setup tasks to get started.

The first step is to setup your administration user. The password requires a numerical character and must not be
longer than 8 chars. You'll then need to log in. Use 'admin' as the username and the password you've entered.

At this point, Archiva is fully functional - you can use it with the default repositories and guest user. You might like
to explore the user and administrator guides to find other functionality.

The default configuration for Archiva configures two repositories:

- `internal` - a repository for containing released artifacts. This is connected by proxy to the central repository, so requests here will
automatically retrieve the remote artifacts.

- `snapshots` - a repository for storing deployed snapshots. This is not proxied to any remote repositories by default.

In addition, the guest user has read access to these repositories, so you can make anonymous requests to either. To try this out, point a web browser at the following URL:
<http://localhost:8080/repository/internal/junit/junit/3.8.1/junit-3.8.1.jar>. Though the artifact is not present locally, you will see in the Archiva logs that it is
downloaded from the central repository, and then handed back to the browser and downloaded from Archiva. Future requests for the artifact will be much faster as they need not be
downloaded from the central repository.

Once this artifact is downloaded, Archiva automatically indexes it, so you can access its information at the following URL: <http://localhost:8080/index.html#artifact/junit/junit>.
It will also be available from the search interface.
