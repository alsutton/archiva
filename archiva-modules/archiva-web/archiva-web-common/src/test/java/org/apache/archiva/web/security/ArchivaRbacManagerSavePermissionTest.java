package org.apache.archiva.web.security;

/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

import org.apache.archiva.redback.rbac.Operation;
import org.apache.archiva.redback.rbac.Permission;
import org.apache.archiva.redback.rbac.RBACManager;
import org.apache.archiva.redback.rbac.RbacManagerException;
import org.apache.archiva.redback.rbac.Resource;
import org.apache.archiva.redback.rbac.Role;
import org.junit.Before;
import org.junit.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/**
 * Which of the configured RBAC managers {@link ArchivaRbacManager#savePermission} asks to write.
 *
 * <p>This class fronts every RBAC manager in the instance: the writable one that holds the grant records, plus any
 * number of read-only ones such as the LDAP directory a deployment publishes roles from. Every other write in it -
 * {@code getRbacManagerForWrite}, {@code saveRole}, {@code saveRoles}, {@code saveOperation}, {@code saveResource},
 * {@code saveUserAssignment} - is guarded by {@code if ( !rbacManager.isReadOnly() )}. The one for permissions read
 * {@code if ( rbacManager.isReadOnly() )}.
 *
 * <p>On the ordinary topology - one writable manager and nothing read-only - the body of that condition never runs:
 * no manager is asked to save, nothing throws, and the caller is handed its own transient object back having been
 * told the save worked. The permission an administrator added to restrict a repository is in no store, and every
 * later request is served by the access rules from before the change. What does get written is the read-only
 * manager: a directory this instance does not own, asked for a write it either refuses or takes.
 *
 * <p>{@code saveRole} is the control here. It is the same shape of method in the same class with the guard the right
 * way round, so what these tests watch failing is one condition and not the wiring around it.
 *
 * @see <a href="https://github.com/alsutton/archiva/issues/74">issue #74</a>
 */
public class ArchivaRbacManagerSavePermissionTest
{
    private static final String WRITABLE = "memory";

    private static final String READ_ONLY = "ldap";

    private static final String PERMISSION_NAME = "archiva.repository.read-repository.payroll";

    private static final String ROLE_NAME = "archiva.repository.manager.payroll";

    /** Writes the store was asked to make, as {@code method:name}. */
    private final List<String> writesToStore = new ArrayList<>();

    /** The same for the read-only directory, which should never see one. */
    private final List<String> writesToDirectory = new ArrayList<>();

    /** What the store hands back when it accepts, standing for the copy it kept. */
    private Permission stored;

    /** When set, the store refuses writes with it instead of accepting them. */
    private Exception storeRefuses;

    private ArchivaRbacManager rbacManager;

    @Before
    public void setUp()
        throws Exception
    {
        stored = null;
        storeRefuses = null;
        writesToStore.clear();
        writesToDirectory.clear();

        Map<String, RBACManager> managers = new LinkedHashMap<>();
        managers.put( WRITABLE, manager( WRITABLE, false, writesToStore ) );
        managers.put( READ_ONLY, manager( READ_ONLY, true, writesToDirectory ) );

        rbacManager = new ArchivaRbacManager();
        inject( rbacManager, "rbacManagersPerId", managers );
    }

    // ---------------------------------------------------------------------------------------------
    // who is asked to write
    // ---------------------------------------------------------------------------------------------

    /**
     * The permission an administrator saved reaches the manager that can hold it, and the caller is given back what
     * that manager kept.
     */
    @Test
    public void aSavedPermissionIsWrittenToTheWritableManager()
        throws Exception
    {
        stored = permission( PERMISSION_NAME + " as the store keeps it" );

        Permission returned = rbacManager.savePermission( permission( PERMISSION_NAME ) );

        assertEquals( "the permission never reached the writable manager",
                      Collections.singletonList( "savePermission:" + PERMISSION_NAME ), writesToStore );
        assertSame( "the caller was not handed what the store kept", stored, returned );
    }

    /**
     * The directory is not written to. It publishes roles this instance does not own, and granting a permission here
     * is not an edit to somebody else's records.
     */
    @Test
    public void aSavedPermissionIsNeverWrittenToTheReadOnlyManager()
        throws Exception
    {
        rbacManager.savePermission( permission( PERMISSION_NAME ) );

        assertFalse( "a read-only manager was asked to write: " + writesToDirectory,
                     writesToDirectory.contains( "savePermission:" + PERMISSION_NAME ) );
    }

    /**
     * A store that refuses says so out loud. While the write is skipped the exception it would have raised cannot
     * happen, so nothing is thrown and a permission store nobody can write to reads as an instance with no
     * permissions to change.
     */
    @Test
    public void aWriteTheStoreRefusesIsReported()
        throws Exception
    {
        storeRefuses = new RbacManagerException( "the permission store is on a disk that is full" );

        RbacManagerException refused =
            assertThrows( RbacManagerException.class, () -> rbacManager.savePermission( permission( PERMISSION_NAME ) ) );

        assertTrue( "not the store's own reason: " + refused.getMessage(),
                    refused.getMessage().contains( "disk that is full" ) );
        assertFalse( "the failure was answered by writing to the directory instead: " + writesToDirectory,
                     writesToDirectory.contains( "savePermission:" + PERMISSION_NAME ) );
    }

    // ---------------------------------------------------------------------------------------------
    // the control
    // ---------------------------------------------------------------------------------------------

    /**
     * Roles go through the same fan-out with the guard written the way permissions should have been. This passes on
     * main and is here so that a failure above names the one method that differs.
     */
    @Test
    public void aSavedRoleGoesToTheWritableManagerAndNotTheDirectory()
        throws Exception
    {
        rbacManager.saveRole( role( ROLE_NAME ) );

        assertEquals( Collections.singletonList( "saveRole:" + ROLE_NAME ), writesToStore );
        assertFalse( writesToDirectory.contains( "saveRole:" + ROLE_NAME ) );
    }

    // ---------------------------------------------------------------------------------------------
    // the pieces
    // ---------------------------------------------------------------------------------------------

    /** An {@link RBACManager} that records the writes it is asked to make, and can be told to refuse. */
    private RBACManager manager( String id, boolean readOnly, List<String> calls )
    {
        return (RBACManager) Proxy.newProxyInstance( ArchivaRbacManagerSavePermissionTest.class.getClassLoader(),
                                                    new Class<?>[] { RBACManager.class },
                                                    ( proxy, method, args ) -> {
                                                        String name = method.getName();
                                                        switch ( name )
                                                        {
                                                            case "isReadOnly":
                                                                return readOnly;
                                                            case "getId":
                                                                return id;
                                                            case "getDescriptionKey":
                                                                return "rbac." + id;
                                                            case "savePermission":
                                                                calls.add( "savePermission:" + name( args[0] ) );
                                                                if ( storeRefuses != null )
                                                                {
                                                                    throw storeRefuses;
                                                                }
                                                                return stored != null ? stored : args[0];
                                                            case "saveRole":
                                                                calls.add( "saveRole:" + name( args[0] ) );
                                                                return args[0];
                                                            default:
                                                                return answer( proxy, name, args );
                                                        }
                                                    } );
    }

    private static String name( Object rbacObject )
    {
        if ( rbacObject instanceof Permission )
        {
            return ( (Permission) rbacObject ).getName();
        }
        if ( rbacObject instanceof Role )
        {
            return ( (Role) rbacObject ).getName();
        }
        return String.valueOf( rbacObject );
    }

    /** A {@link Permission} that knows its name, which is all the code under test reads from it. */
    private static Permission permission( String name )
    {
        return (Permission) Proxy.newProxyInstance( ArchivaRbacManagerSavePermissionTest.class.getClassLoader(),
                                                   new Class<?>[] { Permission.class },
                                                   ( proxy, method, args ) -> {
                                                       switch ( method.getName() )
                                                       {
                                                           case "getName":
                                                               return name;
                                                           case "getOperation":
                                                               return operation( "read-repository" );
                                                           case "getResource":
                                                               return resource( "archiva.repository.payroll" );
                                                           default:
                                                               return answer( proxy, method.getName(), args );
                                                       }
                                                   } );
    }

    private static Role role( String name )
    {
        return (Role) Proxy.newProxyInstance( ArchivaRbacManagerSavePermissionTest.class.getClassLoader(),
                                             new Class<?>[] { Role.class }, ( proxy, method, args ) -> {
                                                 switch ( method.getName() )
                                                 {
                                                     case "getName":
                                                         return name;
                                                     case "getRolePermissions":
                                                         return Collections.emptySet();
                                                     default:
                                                         return answer( proxy, method.getName(), args );
                                                 }
                                             } );
    }

    private static Operation operation( String name )
    {
        return (Operation) Proxy.newProxyInstance( ArchivaRbacManagerSavePermissionTest.class.getClassLoader(),
                                                  new Class<?>[] { Operation.class },
                                                  ( proxy, method, args ) -> "getName".equals( method.getName() )
                                                      ? name
                                                      : answer( proxy, method.getName(), args ) );
    }

    private static Resource resource( String id )
    {
        return (Resource) Proxy.newProxyInstance( ArchivaRbacManagerSavePermissionTest.class.getClassLoader(),
                                                 new Class<?>[] { Resource.class },
                                                 ( proxy, method, args ) -> "getResourceId".equals( method.getName() )
                                                     ? id
                                                     : answer( proxy, method.getName(), args ) );
    }

    /**
     * The answers every object owes, so a fake inside a collection or a log message behaves, plus a harmless default
     * for the rest: {@code false} where a boolean is expected, {@code null} everywhere else.
     */
    private static Object answer( Object proxy, String methodName, Object[] args )
    {
        if ( "toString".equals( methodName ) )
        {
            return proxy.getClass().getInterfaces()[0].getSimpleName() + "@test";
        }
        if ( "hashCode".equals( methodName ) )
        {
            return System.identityHashCode( proxy );
        }
        if ( "equals".equals( methodName ) )
        {
            return proxy == args[0];
        }
        return methodName.startsWith( "is" ) || methodName.startsWith( "has" ) ? Boolean.FALSE : null;
    }

    private static void inject( Object target, String fieldName, Object value )
        throws Exception
    {
        for ( Class<?> c = target.getClass(); c != null; c = c.getSuperclass() )
        {
            try
            {
                Field field = c.getDeclaredField( fieldName );
                field.setAccessible( true );
                field.set( target, value );
                return;
            }
            catch ( NoSuchFieldException e )
            {
                // it belongs to a superclass, and the loop will get there
            }
        }
        throw new AssertionError( target.getClass() + " has no field " + fieldName );
    }
}
