# Manage Archiva Users

Archiva uses the [Redback](http://archiva.apache.org/redback/) security framework for managing repository security. When the server is first started,
you will be prompted to create an administration user. This user will be given permission to administer all aspects of the system (as well as
access to all of the repositories). This user can then be used to grant permissions to other users.

A guest user is also created by default, and given read access to the default repositories (`internal` and `snapshots`). Repositories with
guest user access can be accessed without the use of a username and password (or without being logged in to the web interface).

## Manage Users

The ui provide a screen to manage your users. You can:

- Delete user

- Edit user

- Block user

- Force user to change password on next login

![users-list](../images/users-list.png)

## User edition

### Screen to manage a user

![user-edit](../images/user-edit.png)

### Screen to manage user roles

![user-edit-roles](../images/user-edit-roles.png)

## User registration

**NOTE:** you can disable user registration using [UI configuration](./ui-configuration.html)

### User can registrer using the ui

![user-register](../images/user-register.png)

### Fill in the registration form

![user-register-form](../images/user-register-form.png)
