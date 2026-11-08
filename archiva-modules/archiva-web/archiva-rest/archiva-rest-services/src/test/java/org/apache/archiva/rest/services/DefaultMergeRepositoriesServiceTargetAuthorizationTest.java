package org.apache.archiva.rest.services;

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

import org.apache.archiva.metadata.repository.MetadataRepository;
import org.apache.archiva.metadata.repository.RepositorySession;
import org.apache.archiva.metadata.repository.RepositorySessionFactory;
import org.apache.archiva.rest.api.services.ArchivaRestServiceException;
import org.apache.archiva.stagerepository.merge.RepositoryMerger;
import org.junit.Before;
import org.junit.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * {@code MergeRepositoriesService#mergeRepositories} is authorized with
 * {@code @RedbackAuthorization(resource = "{sourceRepositoryId}")} - one resource, the source. The target of a merge
 * is written into and rescanned, and nothing ever asked whether the caller may do that to it, so managing one
 * repository was enough to push its artifacts into every other repository in the instance.
 *
 * <p>{@code getMergeConflictedArtifacts} reads the target's metadata and had the same hole.
 *
 * @see <a href="https://github.com/alsutton/archiva/issues/65">issue #65</a>
 */
public class DefaultMergeRepositoriesServiceTargetAuthorizationTest
{
    /** Repositories the caller may write to. */
    private static final String STAGING = "staging";

    private static final String RELEASES = "releases";

    /** A repository the caller may not write to. */
    private static final String PAYROLL = "payroll-internal";

    private static final int FORBIDDEN = 403;

    /** Calls the merger was told to make, in call order. */
    private final List<String> mergerCalls = new ArrayList<>();

    /** Metadata sessions the service opened. */
    private int sessions;

    private List<String> manageableRepos;

    private DefaultMergeRepositoriesService mergeService;

    @Before
    public void setUp()
        throws Exception
    {
        manageableRepos = new ArrayList<>( Arrays.asList( STAGING, RELEASES ) );
        mergeService = new TestableMergeRepositoriesService();
        inject( mergeService, "repositoryMerger", Collections.<RepositoryMerger>singletonList( recordingMerger() ) );
        inject( mergeService, "repositoryRegistry", repositoryRegistry() );
        inject( mergeService, "repositorySessionFactory", metadataSessionFactory() );
        inject( mergeService, "repositoryTaskScheduler", taskScheduler() );
    }

    /**
     * The disclosure: a merge is a write, and the written-to repository was never authorized.
     */
    @Test
    public void mergingIntoARepositoryTheCallerDoesNotManageIsRefused()
        throws Exception
    {
        assertRefused( () -> mergeService.mergeRepositories( STAGING, PAYROLL, false ) );

        assertEquals( "the merger must not be reached", Collections.emptyList(), mergerCalls );
        assertEquals( "not even a metadata session should be opened", 0, sessions );
    }

    /**
     * Reading conflicts back is reading the target's metadata, so the same rights apply.
     */
    @Test
    public void readingConflictsAgainstAnUnmanagedTargetIsRefused()
        throws Exception
    {
        assertRefused( () -> mergeService.getMergeConflictedArtifacts( STAGING, PAYROLL ) );

        assertEquals( Collections.emptyList(), mergerCalls );
    }

    /**
     * A caller who may manage neither repository cannot merge either way, which also means the endpoint cannot be
     * used to probe which repository ids exist.
     */
    @Test
    public void aCallerManagingNothingCanMergeNowhere()
        throws Exception
    {
        manageableRepos = Collections.emptyList();

        assertRefused( () -> mergeService.mergeRepositories( STAGING, RELEASES, false ) );
        assertRefused( () -> mergeService.getMergeConflictedArtifacts( STAGING, RELEASES ) );

        assertEquals( Collections.emptyList(), mergerCalls );
    }

    /**
     * Guards the fix against over-blocking: a merge between two repositories the caller manages still merges.
     */
    @Test
    public void mergingBetweenTwoManagedRepositoriesStillMerges()
        throws Exception
    {
        mergeService.mergeRepositories( STAGING, RELEASES, false );

        assertEquals( Collections.singletonList( "merge(" + STAGING + "->" + RELEASES + ")" ), mergerCalls );
        assertTrue( "the merge needs a metadata session", sessions > 0 );
    }

    /**
     * And reading the conflicts of a legitimate merge still works.
     */
    @Test
    public void readingConflictsOfALegitimateMergeStillReachesTheMerger()
        throws Exception
    {
        mergeService.getMergeConflictedArtifacts( STAGING, RELEASES );

        assertEquals( Collections.singletonList( "getConflictingArtifacts(" + STAGING + "->" + RELEASES + ")" ),
                      mergerCalls );
    }

    // ----------------------------------------------------------------------------------------------
    // fixtures
    // ----------------------------------------------------------------------------------------------

    private interface Invocation
    {
        void call()
            throws Exception;
    }

    private void assertRefused( Invocation invocation )
    {
        try
        {
            invocation.call();
            fail( "expected the merge to be refused, but it was carried out" );
        }
        catch ( ArchivaRestServiceException e )
        {
            assertEquals( "refusing a merge target must answer 403", FORBIDDEN, e.getHttpErrorCode() );
        }
        catch ( Exception e )
        {
            fail( "expected an ArchivaRestServiceException but got " + e );
        }
    }

    /**
     * Records what the service asks the merger to do, so that "refused" can be told apart from "carried out".
     */
    private RepositoryMerger recordingMerger()
    {
        return (RepositoryMerger) Proxy.newProxyInstance( getClass().getClassLoader(),
                                                          new Class<?>[] { RepositoryMerger.class },
                                                          ( proxy, method, args ) -> {
                                                              if ( Object.class.equals(
                                                                  method.getDeclaringClass() ) )
                                                              {
                                                                  return "recording merger";
                                                              }
                                                              if ( "supportsRepository".equals( method.getName() ) )
                                                              {
                                                                  return Boolean.TRUE;
                                                              }
                                                              if ( "merge".equals( method.getName() ) )
                                                              {
                                                                  mergerCalls.add(
                                                                      "merge(" + args[1] + "->" + args[2] + ")" );
                                                                  return null;
                                                              }
                                                              if ( "getConflictingArtifacts".equals(
                                                                  method.getName() ) )
                                                              {
                                                                  mergerCalls.add( "getConflictingArtifacts(" + args[1]
                                                                      + "->" + args[2] + ")" );
                                                                  return new ArrayList<>();
                                                              }
                                                              return defaultValue( method.getReturnType() );
                                                          } );
    }

    /**
     * Serves the repository lookups the merge path performs; the release schemes are set so that the plain
     * {@code merge} branch is taken.
     */
    private Object repositoryRegistry()
    {
        return Proxy.newProxyInstance( getClass().getClassLoader(),
                                       new Class<?>[] {
                                           org.apache.archiva.repository.RepositoryRegistry.class },
                                       ( proxy, method, args ) -> {
                                           if ( "getManagedRepository".equals( method.getName() ) )
                                           {
                                               return managedRepository();
                                           }
                                           if ( "getRepository".equals( method.getName() ) )
                                           {
                                               return repository();
                                           }
                                           return defaultValue( method.getReturnType() );
                                       } );
    }

    private Object managedRepository()
    {
        return Proxy.newProxyInstance( getClass().getClassLoader(),
                                       new Class<?>[] {
                                           org.apache.archiva.repository.ManagedRepository.class },
                                       ( proxy, method, args ) -> {
                                           if ( "getActiveReleaseSchemes".equals( method.getName() ) )
                                           {
                                               return EnumSet.allOf(
                                                   org.apache.archiva.repository.ReleaseScheme.class );
                                           }
                                           return defaultValue( method.getReturnType() );
                                       } );
    }

    private Object repository()
    {
        return Proxy.newProxyInstance( getClass().getClassLoader(),
                                       new Class<?>[] { org.apache.archiva.repository.Repository.class },
                                       ( proxy, method, args ) -> defaultValue( method.getReturnType() ) );
    }

    /**
     * Absorbs the rescan the merge schedules at the end of a successful merge.
     */
    private Object taskScheduler()
    {
        return Proxy.newProxyInstance( getClass().getClassLoader(),
                                       new Class<?>[] {
                                           org.apache.archiva.scheduler.repository.model.RepositoryArchivaTaskScheduler.class },
                                       ( proxy, method, args ) -> defaultValue( method.getReturnType() ) );
    }

    private static void inject( Object target, String fieldName, Object value )
        throws Exception
    {
        for ( Class<?> type = target.getClass(); type != null; type = type.getSuperclass() )
        {
            try
            {
                Field field = type.getDeclaredField( fieldName );
                field.setAccessible( true );
                field.set( target, value );
                return;
            }
            catch ( NoSuchFieldException expected )
            {
                // the fields are declared on the service and its base class
            }
        }
        throw new NoSuchFieldException( fieldName );
    }

    private RepositorySessionFactory metadataSessionFactory()
    {
        MetadataRepository metadataRepository =
            (MetadataRepository) Proxy.newProxyInstance( getClass().getClassLoader(),
                                                         new Class<?>[] { MetadataRepository.class },
                                                         ( proxy, method, args ) -> defaultValue(
                                                             method.getReturnType() ) );
        // RepositorySession is a concrete class whose close() only saves a dirty session.
        RepositorySession session = new RepositorySession( metadataRepository, null );

        return (RepositorySessionFactory) Proxy.newProxyInstance( getClass().getClassLoader(),
                                                                  new Class<?>[] {
                                                                      RepositorySessionFactory.class },
                                                                  ( proxy, method, args ) -> {
                                                                      if ( "createSession".equals(
                                                                          method.getName() ) )
                                                                      {
                                                                          sessions++;
                                                                          return session;
                                                                      }
                                                                      return defaultValue( method.getReturnType() );
                                                                  } );
    }

    private static Object defaultValue( Class<?> returnType )
    {
        if ( List.class.equals( returnType ) )
        {
            return new ArrayList<>();
        }
        if ( !returnType.isPrimitive() )
        {
            return null;
        }
        if ( boolean.class.equals( returnType ) )
        {
            return Boolean.FALSE;
        }
        if ( void.class.equals( returnType ) )
        {
            return null;
        }
        return 0;
    }

    /**
     * A merge service whose caller may manage exactly {@link #manageableRepos}. The real
     * {@code getManageableRepos()} asks Redback, which is not what these tests are about.
     */
    private class TestableMergeRepositoriesService
        extends DefaultMergeRepositoriesService
    {
        @Override
        protected List<String> getManageableRepos()
        {
            return manageableRepos;
        }

        @Override
        protected List<String> getObservableRepos()
        {
            return manageableRepos;
        }

        @Override
        protected String getPrincipal()
        {
            return "repository-manager";
        }
    }
}
