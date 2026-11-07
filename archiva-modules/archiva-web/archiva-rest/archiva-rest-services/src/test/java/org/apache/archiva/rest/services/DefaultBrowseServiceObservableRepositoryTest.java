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
import org.junit.Before;
import org.junit.Test;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * The six browse endpoints below are annotated {@code @RedbackAuthorization(noPermission = true,
 * noRestriction = true)}, which makes the interceptor log "noRestriction() so skip permission check" and let the
 * request through without credentials. The service body is therefore the only access control on them, and its own
 * {@code getSelectedRepos()} guard - used by every sibling method - was skipped here.
 *
 * <p>What is asserted is which repository the metadata store was <em>asked</em> to read. A response that happens
 * to render nothing is not a fix: the store is the component that knows what is in a private repository, and
 * naming it is the disclosure.
 *
 * <p>The service is constructed by hand with the observable set pinned, so the guard is the only thing under test.
 *
 * @see <a href="https://github.com/alsutton/archiva/issues/64">issue #64</a>
 */
public class DefaultBrowseServiceObservableRepositoryTest
{
    /** Repositories the caller this test acts as may observe. */
    private static final String RELEASES = "releases";

    private static final String SNAPSHOTS = "snapshots";

    /** A repository that exists but is hidden from that caller. */
    private static final String PAYROLL = "payroll-internal";

    private static final int FORBIDDEN = 403;

    /** Calls the metadata store was asked to make, as {@code method(repositoryId)}, in call order. */
    private final List<String> storeCalls = new ArrayList<>();

    private List<String> observableRepos;

    private DefaultBrowseService browseService;

    @Before
    public void setUp()
        throws Exception
    {
        observableRepos = new ArrayList<>( Arrays.asList( RELEASES, SNAPSHOTS ) );
        browseService = new TestableBrowseService();
        inject( browseService, "repositorySessionFactory", metadataSessionFactory() );
    }

    // ----------------------------------------------------------------------------------------------
    // getArtifacts
    // ----------------------------------------------------------------------------------------------

    /**
     * The disclosure: listing the contents of a named repository never consulted the caller's rights, so any
     * unauthenticated request naming a private repository was told what it holds.
     */
    @Test
    public void listingAHiddenRepositoryIsRefused()
        throws Exception
    {
        assertRefused( () -> browseService.getArtifacts( PAYROLL ) );

        assertEquals( "the hidden repository must never reach the metadata store", Collections.emptyList(),
                      storeCalls );
    }

    /**
     * Guards the fix against over-blocking: listing a repository the caller can observe still works.
     */
    @Test
    public void listingAnObservableRepositoryStillReadsIt()
        throws Exception
    {
        browseService.getArtifacts( RELEASES );

        assertEquals( Collections.singletonList( "getArtifacts(" + RELEASES + ")" ), storeCalls );
    }

    // ----------------------------------------------------------------------------------------------
    // getArtifactsByProjectVersionMetadata / getArtifactsByMetadata / getArtifactsByProperty
    // ----------------------------------------------------------------------------------------------

    @Test
    public void projectVersionMetadataLookupOfAHiddenRepositoryIsRefused()
        throws Exception
    {
        assertRefused( () -> browseService.getArtifactsByProjectVersionMetadata( "checker", "MRT", PAYROLL ) );

        assertEquals( Collections.emptyList(), storeCalls );
    }

    @Test
    public void metadataLookupOfAHiddenRepositoryIsRefused()
        throws Exception
    {
        assertRefused( () -> browseService.getArtifactsByMetadata( "checker", "MRT", PAYROLL ) );

        assertEquals( Collections.emptyList(), storeCalls );
    }

    @Test
    public void propertyLookupOfAHiddenRepositoryIsRefused()
        throws Exception
    {
        assertRefused( () -> browseService.getArtifactsByProperty( "checker", "MRT", PAYROLL ) );

        assertEquals( Collections.emptyList(), storeCalls );
    }

    /**
     * Without a repository named the answer is every repository the caller can observe, which is what the
     * annotated endpoints are for. The hidden repository must not join the fan-out.
     */
    @Test
    public void metadataLookupWithoutARepositoryFansOutOverObservableRepositoriesOnly()
        throws Exception
    {
        browseService.getArtifactsByMetadata( "checker", "MRT", null );

        assertEquals( Arrays.asList( "getArtifactsByAttribute(" + RELEASES + ")",
                                      "getArtifactsByAttribute(" + SNAPSHOTS + ")" ), storeCalls );
    }

    @Test
    public void projectVersionPropertyLookupWithoutARepositoryFansOutOverObservableRepositoriesOnly()
        throws Exception
    {
        browseService.getArtifactsByProperty( "checker", "MRT", "" );

        assertEquals( Arrays.asList( "getArtifactsByProjectVersionAttribute(" + RELEASES + ")",
                                      "getArtifactsByProjectVersionAttribute(" + SNAPSHOTS + ")" ), storeCalls );
    }

    /**
     * A caller that can observe nothing must be answered with nothing. The old behaviour was to query every
     * repository in the instance.
     */
    @Test
    public void metadataLookupByACallerObservingNothingReadsNothing()
        throws Exception
    {
        observableRepos = Collections.emptyList();

        assertTrue( "nothing readable means an empty answer",
                    browseService.getArtifactsByMetadata( "checker", "MRT", null ).isEmpty() );
        assertEquals( Collections.emptyList(), storeCalls );
    }

    // ----------------------------------------------------------------------------------------------
    // searchArtifacts
    // ----------------------------------------------------------------------------------------------

    @Test
    public void searchOfAHiddenRepositoryIsRefused()
        throws Exception
    {
        assertRefused( () -> browseService.searchArtifacts( "commons-io", PAYROLL, Boolean.FALSE ) );

        assertEquals( Collections.emptyList(), storeCalls );
    }

    @Test
    public void metadataSearchOfAHiddenRepositoryIsRefused()
        throws Exception
    {
        assertRefused( () -> browseService.searchArtifacts( "checker", "MRT", PAYROLL, Boolean.TRUE ) );

        assertEquals( Collections.emptyList(), storeCalls );
    }

    @Test
    public void searchWithoutARepositoryFansOutOverObservableRepositoriesOnly()
        throws Exception
    {
        browseService.searchArtifacts( "commons-io", null, Boolean.FALSE );

        assertEquals( Arrays.asList( "searchArtifacts(" + RELEASES + ")", "searchArtifacts(" + SNAPSHOTS + ")" ),
                      storeCalls );
    }

    @Test
    public void metadataSearchWithoutARepositoryFansOutOverObservableRepositoriesOnly()
        throws Exception
    {
        browseService.searchArtifacts( "checker", "MRT", null, Boolean.FALSE );

        assertEquals( Arrays.asList( "searchArtifacts(" + RELEASES + ")", "searchArtifacts(" + SNAPSHOTS + ")" ),
                      storeCalls );
    }

    @Test
    public void searchOfAnObservableRepositoryStillRuns()
        throws Exception
    {
        browseService.searchArtifacts( "commons-io", SNAPSHOTS, Boolean.FALSE );

        assertEquals( Collections.singletonList( "searchArtifacts(" + SNAPSHOTS + ")" ), storeCalls );
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
            fail( "expected the request to be refused, but it was answered" );
        }
        catch ( ArchivaRestServiceException e )
        {
            assertEquals( "refusing a browse request must answer 403", FORBIDDEN, e.getHttpErrorCode() );
        }
        catch ( Exception e )
        {
            fail( "expected an ArchivaRestServiceException but got " + e );
        }
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

    /**
     * A metadata store that records the repository each query names. Every other call returns a default so the
     * service can walk its normal path; {@code buildArtifacts} turns the empty lists into empty answers.
     */
    private RepositorySessionFactory metadataSessionFactory()
    {
        InvocationHandler store = ( proxy, method, args ) -> {
            if ( Object.class.equals( method.getDeclaringClass() ) )
            {
                return "toString".equals( method.getName() ) ? "recording metadata store" : null;
            }
            String repositoryId = repositoryIdOf( method, args );
            if ( repositoryId != null )
            {
                storeCalls.add( method.getName() + "(" + repositoryId + ")" );
            }
            return defaultValue( method.getReturnType() );
        };

        MetadataRepository metadataRepository =
            (MetadataRepository) Proxy.newProxyInstance( getClass().getClassLoader(),
                                                         new Class<?>[] { MetadataRepository.class }, store );
        // RepositorySession is a concrete class whose close() only saves a dirty session, so a real one over the
        // recording store is cheaper than trying to stand in for it.
        RepositorySession session = new RepositorySession( metadataRepository, null );

        return (RepositorySessionFactory) Proxy.newProxyInstance( getClass().getClassLoader(),
                                                                  new Class<?>[] { RepositorySessionFactory.class },
                                                                  ( proxy, method, args ) ->
                                                                      "createSession".equals( method.getName() )
                                                                          ? session
                                                                          : defaultValue(
                                                                              method.getReturnType() ) );
    }

    /**
     * The repository argument of the six queries under test, or {@code null} for methods this test does not care
     * about. It moves position with the query, hence the lookup by signature.
     */
    private static String repositoryIdOf( Method method, Object[] args )
    {
        String name = method.getName();
        int index;
        if ( "getArtifactsByProjectVersionFacet".equals( name ) || "getArtifactsByAttribute".equals( name )
            || "getArtifactsByProjectVersionAttribute".equals( name ) )
        {
            index = 3;
        }
        else if ( "getArtifacts".equals( name ) && method.getParameterCount() == 2 )
        {
            index = 1;
        }
        else if ( "searchArtifacts".equals( name ) )
        {
            index = 1;
        }
        else
        {
            return null;
        }
        return args == null || args.length <= index ? null : String.valueOf( args[index] );
    }

    private static Object defaultValue( Class<?> returnType )
    {
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
     * A browse service whose caller can observe exactly {@link #observableRepos}. The real
     * {@code getObservableRepos()} asks Redback, which is not what these tests are about.
     */
    private class TestableBrowseService
        extends DefaultBrowseService
    {
        @Override
        protected List<String> getObservableRepos()
        {
            return observableRepos;
        }

        @Override
        protected String getPrincipal()
        {
            return "developer";
        }
    }
}
