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
import org.apache.archiva.metadata.repository.stats.model.RepositoryStatisticsManager;
import org.apache.archiva.rest.api.services.ArchivaRestServiceException;
import org.junit.Before;
import org.junit.Test;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * {@code getStatisticsReport} hands its {@code repository} query parameters straight to the statistics manager,
 * while the sibling {@code getHealthReport} in the same class filters by observable repository first. Repository
 * statistics say how much a repository holds, how often it is downloaded and when it was last touched, which is
 * exactly what an outsider probing a private repository wants.
 *
 * <p>The tests below assert which repositories the statistics manager is asked about, and that a request the caller
 * has no rights to is answered with a refusal rather than an empty list: "you may not look" and "there is nothing
 * here" have to stay distinguishable.
 *
 * @see <a href="https://github.com/alsutton/archiva/issues/67">issue #67</a>
 */
public class DefaultReportRepositoriesServiceStatisticsAccessTest
{
    private static final String RELEASES = "releases";

    private static final String SNAPSHOTS = "snapshots";

    private static final String PAYROLL = "payroll-internal";

    private static final int FORBIDDEN = 403;

    /** Repositories the statistics manager was asked about, in call order. */
    private final List<String> queried = new ArrayList<>();

    private List<String> observableRepos;

    private DefaultReportRepositoriesService reportService;

    @Before
    public void setUp()
        throws Exception
    {
        observableRepos = new ArrayList<>( Arrays.asList( RELEASES, SNAPSHOTS ) );
        reportService = new TestableReportRepositoriesService();
        inject( reportService, "repositorySessionFactory", metadataSessionFactory() );
        inject( reportService, "repositoryStatisticsManager", statisticsManager() );
    }

    /**
     * The disclosure: one parameter, one private repository, and its statistics.
     */
    @Test
    public void statisticsOfAHiddenRepositoryAreRefused()
        throws Exception
    {
        assertRefused( Collections.singletonList( PAYROLL ) );

        assertEquals( "the hidden repository must never reach the statistics manager", Collections.emptyList(),
                      queried );
    }

    /**
     * Guards the fix against over-blocking.
     */
    @Test
    public void statisticsOfAnObservableRepositoryStillComeBack()
        throws Exception
    {
        reportService.getStatisticsReport( Collections.singletonList( RELEASES ), 10, null, null );

        assertEquals( Collections.singletonList( RELEASES ), queried );
    }

    /**
     * A partially visible request is narrowed to what the caller may see instead of being answered in full.
     */
    @Test
    public void aMixedRequestIsNarrowedToTheObservableRepositories()
        throws Exception
    {
        reportService.getStatisticsReport( Arrays.asList( PAYROLL, RELEASES ), 10, null, null );

        assertEquals( Collections.singletonList( RELEASES ), queried );
    }

    /**
     * A caller who can observe none of the repositories it named is refused; telling it "no statistics" would leak
     * that the report ran at all and would be indistinguishable from a genuinely empty repository.
     */
    @Test
    public void aCallerObservingNothingIsRefusedRatherThanToldThereIsNothing()
        throws Exception
    {
        observableRepos = Collections.emptyList();

        assertRefused( Arrays.asList( RELEASES, SNAPSHOTS ) );

        assertEquals( Collections.emptyList(), queried );
    }

    /**
     * The pre-existing "you named nothing" error stays as it was: it is a malformed request, not a denied one.
     */
    @Test
    public void namingNoRepositoriesIsStillReportedAsAMissingParameter()
        throws Exception
    {
        try
        {
            reportService.getStatisticsReport( Collections.<String>emptyList(), 10, null, null );
            fail( "expected the missing parameter error" );
        }
        catch ( ArchivaRestServiceException e )
        {
            assertEquals( "report.statistics.report.missing-repositories", e.getMessage() );
            assertTrue( "a missing parameter must not be reported as a denial",
                        FORBIDDEN != e.getHttpErrorCode() );
        }
    }

    // ----------------------------------------------------------------------------------------------
    // fixtures
    // ----------------------------------------------------------------------------------------------

    private void assertRefused( List<String> repositoriesId )
        throws Exception
    {
        try
        {
            reportService.getStatisticsReport( repositoriesId, 10, null, null );
            fail( "expected the request to be refused, but statistics were returned" );
        }
        catch ( ArchivaRestServiceException e )
        {
            assertEquals( "refusing a statistics request must answer 403", FORBIDDEN, e.getHttpErrorCode() );
        }
    }

    /**
     * Records the repository each statistics query names.
     */
    private RepositoryStatisticsManager statisticsManager()
    {
        return (RepositoryStatisticsManager) java.lang.reflect.Proxy.newProxyInstance(
            getClass().getClassLoader(), new Class<?>[] { RepositoryStatisticsManager.class },
            ( proxy, method, args ) -> {
                if ( Object.class.equals( method.getDeclaringClass() ) )
                {
                    return "toString".equals( method.getName() ) ? "recording statistics manager" : null;
                }
                if ( "getLastStatistics".equals( method.getName() )
                    || "getStatisticsInRange".equals( method.getName() ) )
                {
                    queried.add( String.valueOf( args[0] ) );
                }
                return defaultValue( method.getReturnType() );
            } );
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
            (MetadataRepository) java.lang.reflect.Proxy.newProxyInstance( getClass().getClassLoader(),
                                                                           new Class<?>[] { MetadataRepository.class },
                                                                           ( proxy, method, args ) -> defaultValue(
                                                                               method.getReturnType() ) );
        // RepositorySession is a concrete class whose close() only saves a dirty session.
        RepositorySession session = new RepositorySession( metadataRepository, null );

        return (RepositorySessionFactory) java.lang.reflect.Proxy.newProxyInstance( getClass().getClassLoader(),
                                                                                    new Class<?>[] {
                                                                                        RepositorySessionFactory.class },
                                                                                    ( proxy, method, args ) ->
                                                                                        "createSession".equals(
                                                                                            method.getName() )
                                                                                            ? session
                                                                                            : defaultValue(
                                                                                                method.getReturnType() ) );
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
     * A report service whose caller can observe exactly {@link #observableRepos}.
     */
    private class TestableReportRepositoriesService
        extends DefaultReportRepositoriesService
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
