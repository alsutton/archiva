# Log Files

To keep track of the Archiva performance and problems, log files are created during runtime.
These files can be found in the `logs/` directory.

- [<<archiva.log>>](./archiva-logs.html) - contains all the start-up information and output logs for Archiva

- [<<archiva-audit.log>>](./audit-logs.html) - contains information regarding the operations performed against
the repositories and configurations being modified. A good example is when an artifact
is deployed to an Archiva repository. The operation will be logged in this file,
with the date and timestamp of when the operation occurred, the userId of who performed
the deployment, and the artifact that was deployed.

- [<<archiva-security-audit.log>>](./security-logs.html) - contains information regarding Archiva's security.
For example, a successful login of a user or a user account is created.

Note since 2.1.0, the log4j2 asyncLogger feature is NOT anymore configured as the default.

If you want to activate it as default add the system property **-DLog4jContextSelector=org.apache.logging.log4j.core.async.AsyncLoggerContextSelector**

This means the default **AsyncLogger.WaitStrategy** and **AsyncLoggerConfig.WaitStrategy** option is Sleep.

If you are in a constrained environment, you can change it to Block (or Yield) (`-DAsyncLogger.WaitStrategy=Block` and/or

## `-DAsyncLoggerConfig.WaitStrategy=Block`)

(See [log4j2 documentation](http://logging.apache.org/log4j/2.x/manual/async.html) )
