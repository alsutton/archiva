# Apache Archiva Redback Runtime Configuration

## %{toc}

## Apache Redback User Manager/RbacManager Implementations

You can choose to switch dynamically

- User Manager Implementations (from Database and/or LDAP).

- RbacManager Implementations (from Database and/or LDAP): to manage if roles management comes from Database and/or LDAP.

![redback-runtime-conf-general](../images/redback-runtime-conf-general.png)

## LDAP configuration

LDAP configuration can now be done via an UI and it's now dynamic (no more need of using vi to edit a file :-) ) and no more need of restarting.

You can test your ldap configuration too.

![ldap-configuration](../images/ldap-configuration.png)

## LDAP Group-Roles mapping

You can map dynamically LDAP Group to Archiva Roles

![ldap-group-roles-mapping](../images/ldap-group-roles-mapping.png)

## Runtime properties

You can now too modify some Redback configuration properties. You have a help button which explains to you what the property is doing.

![redback-properties](../images/redback-properties.png)

## Users Cache

You can enable/disable users cache and configure various ttl values

![redback-runtime-conf-users-cache](../images/redback-runtime-conf-users-cache.png)
