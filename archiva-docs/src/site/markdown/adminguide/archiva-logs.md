# Archiva Logs

Archiva's `logs` directory contains an archiva log file named `archiva.log`, which logs all the startup information and the output logs of Archiva.

A typical record looks like this:

```
2013-05-02 23:15:41,521 [pool-6-thread-1] INFO  org.apache.archiva.scheduler.repository.ArchivaRepositoryScanningTaskExecutor [] - Gathering repository statistics
2013-05-02 23:15:41,582 [pool-6-thread-1] INFO  org.apache.archiva.metadata.repository.stats.DefaultRepositoryStatisticsManager [] - Gathering statistics executed in 60ms
2013-05-02 23:15:41,615 [pool-6-thread-1] INFO  org.apache.archiva.scheduler.repository.ArchivaRepositoryScanningTaskExecutor [] - Finished repository task: RepositoryTask [repositoryId=snapshots, resourceFile=null, scanAll=false, updateRelatedArtifacts=false]
2013-05-02 23:15:41,626 [pool-5-thread-1] INFO  org.apache.archiva.scheduler.indexing.ArchivaIndexingTaskExecutor [] - indexed maven repository: snapshots, onlyUpdate: false, time 106 ms
2013-05-02 23:15:41,673 [WrapperSimpleAppMain] WARN  org.apache.archiva.redback.components.scheduler.DefaultScheduler [] - Will not schedule this job as a job {rj:internal:rg} already exists.
2013-05-02 23:15:41,675 [WrapperSimpleAppMain] WARN  org.apache.archiva.redback.components.scheduler.DefaultScheduler [] - Will not schedule this job as a job {rj:snapshots:rg} already exists.
2013-05-02 23:15:41,680 [WrapperSimpleAppMain] INFO  org.apache.archiva.scheduler.repository.DefaultRepositoryArchivaTaskScheduler [] - Time to initalize DefaultRepositoryArchivaTaskScheduler: 8 ms
2013-05-02 23:15:41,682 [WrapperSimpleAppMain] INFO  org.apache.archiva.web.startup.Banner [] - _________________________
                          __________________________________
               /\_       /                                  \
              /`/@),    |  On behalf of all of the alpacas   |
              |  (~'  __| toiling away on the Apache Archiva |
      _,--.___/  |    \      project team, I would like to   |
    ,' ,     (   |     \         welcome you to Archiva      |
    |  (      \  /      |          1.4-M4-SNAPSHOT           |
     \  )\_/  ,_/       |                                    |
     / /   ( |/         |     http://archiva.apache.org/     |
    ( |    ( |          |     users@archiva.apache.org       |
     \|     \|           \__________________________________/

2013-05-02 23:15:41,707 [WrapperSimpleAppMain] INFO  org.apache.jackrabbit.webdav.server.AbstractWebdavServlet [] - authenticate-header = Basic realm="Jackrabbit Webdav Server"
2013-05-02 23:15:41,708 [WrapperSimpleAppMain] INFO  org.apache.jackrabbit.webdav.server.AbstractWebdavServlet [] - csrf-protection = null
2013-05-02 23:15:41,708 [WrapperSimpleAppMain] INFO  org.apache.jackrabbit.webdav.server.AbstractWebdavServlet [] - createAbsoluteURI = true
2013-05-02 23:15:41,726 [WrapperSimpleAppMain] INFO  org.apache.archiva.webdav.RepositoryServlet [] - initServers done in 18 ms
```
