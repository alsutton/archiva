# Understanding Network Proxy Configuration of Apache Archiva

Archiva uses the terminology "proxy" for two different concepts:

- The remote repository proxying cache, as configured through [proxy connectors](./proxy-connectors.html) between repositories

- Network proxies, which are traditional protocol based proxies (primarily for HTTP access to remote repositories over a firewall)

Network proxies are configured using standard HTTP proxy settings as provided by your network administrator.

Once configured, the network proxy can be attached to operations that access remote resources. At present, this is configured on the
remote repository proxy connectors that need to access the remote repository over the HTTP protocol.

![network-proxies](../images/network-proxies.png)
