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

import org.apache.archiva.indexer.search.RepositorySearch;
import org.apache.archiva.indexer.search.RepositorySearchException;
import org.apache.archiva.indexer.search.SearchFields;
import org.apache.archiva.indexer.search.SearchResultLimits;
import org.apache.archiva.indexer.search.SearchResults;
import org.apache.archiva.maven.model.Artifact;
import org.apache.archiva.metadata.repository.MetadataRepository;
import org.apache.archiva.metadata.repository.RepositorySession;
import org.apache.archiva.metadata.repository.RepositorySessionFactory;
import org.apache.archiva.rest.api.model.ChecksumSearch;
import org.apache.archiva.rest.api.model.SearchRequest;
import org.junit.Before;
import org.junit.Test;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * The search endpoints are annotated {@code @RedbackAuthorization(noPermission = true,
 * noRestriction = true)} and {@code RepositorySearch.search} ignores its principal, so the
 * repository list the service builds is the only access control on these paths. Naming a
 * repository explicitly in the request must never widen what the caller can see.
 *
 * <p>Every test here asserts what the service <em>asked the store to read</em> rather than what
 * came back, because a disclosure that returns nothing useful is still a disclosure: the indexer
 * and the metadata store are the components that decide visibility, and asking them to read a
 * hidden repository hands them the answer they must not surface.
 *
 * <p>The service is built by hand (no Spring, no JCR) with the observable set pinned, so the
 * restriction logic is the only thing under test.
 */
public class DefaultSearchServiceObservableRestrictionTest
{
    /** The only repository the principal this test acts as is allowed to observe. */
    private static final String VISIBLE = "releases";

    /** A repository that exists but is hidden from that principal. */
    private static final String HIDDEN = "payroll-internal";

    /** Repository ids handed to the indexer, in call order. */
    private final List<String> indexedRepoIds = new ArrayList<>();

    /** Repository ids handed to the metadata store, in call order. */
    private final List<String> storedRepoIds = new ArrayList<>();

    private int indexerCalls;

    private int metadataSessions;

    private List<String> observableRepos;

    private RecordingSearch recordingSearch;

    @Before
    public void setUp()
        throws Exception
    {
        observableRepos = new ArrayList<>( Collections.singletonList( VISIBLE ) );
        recordingSearch = new RecordingSearch();
    }

    // ----------------------------------------------------------------------------------------------
    // quickSearchWithRepositories
    // ----------------------------------------------------------------------------------------------

    /**
     * The disclosure itself: the endpoint used the request's repository list verbatim, so naming a
     * hidden repository searched it.
     */
    @Test
    public void namingAHiddenRepositoryDoesNotSearchIt()
        throws Exception
    {
        List<Artifact> results = searchService().quickSearchWithRepositories( request( HIDDEN ) );

        assertEquals( "the hidden repository must never reach the indexer", Collections.emptyList(), indexedRepoIds );
        assertEquals( "the indexer must not be called at all", 0, indexerCalls );
        assertTrue( results.isEmpty() );
    }

    /**
     * A partially visible request must be narrowed, not emptied: the caller keeps what it was
     * already allowed to see.
     */
    @Test
    public void aMixedRequestIsNarrowedToTheObservableRepositories()
        throws Exception
    {
        searchService().quickSearchWithRepositories( request( VISIBLE, HIDDEN ) );

        assertEquals( Collections.singletonList( VISIBLE ), indexedRepoIds );
        assertEquals( 1, indexerCalls );
    }

    /**
     * Guards the fix against over-blocking: with nothing named, the caller still searches
     * everything it can observe.
     */
    @Test
    public void namingNothingStillSearchesEverythingObservable()
        throws Exception
    {
        searchService().quickSearchWithRepositories( request() );

        assertEquals( Collections.singletonList( VISIBLE ), indexedRepoIds );
        assertEquals( 1, indexerCalls );
    }

    /**
     * An empty repository set means "search every index" to the indexer, so a caller who can
     * observe nothing must be turned away before the search is reached.
     */
    @Test
    public void aCallerObservingNothingIsNeverHandedTheWholeIndex()
        throws Exception
    {
        observableRepos = Collections.emptyList();

        List<Artifact> results = searchService().quickSearchWithRepositories( request() );

        assertEquals( 0, indexerCalls );
        assertTrue( results.isEmpty() );
    }

    // ----------------------------------------------------------------------------------------------
    // searchArtifacts
    // ----------------------------------------------------------------------------------------------

    @Test
    public void advancedSearchOfAHiddenRepositoryIsNotRun()
        throws Exception
    {
        SearchRequest searchRequest = request( HIDDEN );
        searchRequest.setGroupId( "com.example" );

        List<Artifact> results = searchService().searchArtifacts( searchRequest );

        assertEquals( Collections.emptyList(), indexedRepoIds );
        assertEquals( 0, indexerCalls );
        assertTrue( results.isEmpty() );
    }

    @Test
    public void advancedSearchIsNarrowedToTheObservableRepositories()
        throws Exception
    {
        SearchRequest searchRequest = request( HIDDEN, VISIBLE );
        searchRequest.setGroupId( "com.example" );

        searchService().searchArtifacts( searchRequest );

        assertEquals( Collections.singletonList( VISIBLE ), indexedRepoIds );
        assertEquals( 1, indexerCalls );
    }

    // ----------------------------------------------------------------------------------------------
    // getArtifactByChecksum
    // ----------------------------------------------------------------------------------------------

    @Test
    public void checksumLookupOfAHiddenRepositoryDoesNotQueryTheStore()
        throws Exception
    {
        List<Artifact> results = searchService().getArtifactByChecksum( new ChecksumSearch(
            Collections.singletonList( HIDDEN ), "da39a3ee5e6b4b0d3255bfef95601890afd80709" ) );

        assertEquals( "the hidden repository must never reach the metadata store", Collections.emptyList(),
                      storedRepoIds );
        assertEquals( "not even a session should be opened", 0, metadataSessions );
        assertTrue( results.isEmpty() );
    }

    /**
     * And again the other direction: a legitimate checksum lookup keeps working.
     */
    @Test
    public void checksumLookupOfAnObservableRepositoryStillHitsTheStore()
        throws Exception
    {
        searchService().getArtifactByChecksum(
            new ChecksumSearch( Arrays.asList( VISIBLE, HIDDEN ), "da39a3ee5e6b4b0d3255bfef95601890afd80709" ) );

        assertEquals( Collections.singletonList( VISIBLE ), storedRepoIds );
    }

    // ----------------------------------------------------------------------------------------------
    // fixtures
    // ----------------------------------------------------------------------------------------------

    private SearchRequest request( String... repositories )
    {
        SearchRequest searchRequest = new SearchRequest();
        searchRequest.setQueryTerms( "commons-io" );
        searchRequest.setRepositories( new ArrayList<>( Arrays.asList( repositories ) ) );
        return searchRequest;
    }

    /**
     * A service whose caller can observe exactly {@link #observableRepos}. The real
     * {@code getObservableRepos()} asks Redback, which is not what these tests are about.
     */
    private DefaultSearchService searchService()
        throws Exception
    {
        TestableSearchService service = new TestableSearchService();
        inject( service, "repositorySearch", recordingSearch );
        inject( service, "repositorySessionFactory", metadataSessionFactory() );
        return service;
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
        InvocationHandler store = new RecordingHandler();
        MetadataRepository metadataRepository =
            (MetadataRepository) Proxy.newProxyInstance( getClass().getClassLoader(),
                                                         new Class<?>[] { MetadataRepository.class }, store );
        // RepositorySession is a concrete class whose close() only saves a dirty session, so a real
        // one over the recording store is cheaper than trying to stand in for it.
        RepositorySession session = new RepositorySession( metadataRepository, null );
        return (RepositorySessionFactory) Proxy.newProxyInstance( getClass().getClassLoader(),
                                                                  new Class<?>[] { RepositorySessionFactory.class },
                                                                  (proxy, method, args) -> {
                                                                      if ( "createSession".equals(
                                                                          method.getName() ) )
                                                                      {
                                                                          metadataSessions++;
                                                                          return session;
                                                                      }
                                                                      return defaultValue( method.getReturnType() );
                                                                  } );
    }

    /**
     * Records which repository the metadata store is asked about. Only
     * {@code getArtifactsByChecksum} matters; every other call returns a default so the service can
     * walk its normal path.
     */
    private class RecordingHandler
        implements InvocationHandler
    {
        @Override
        public Object invoke( Object proxy, Method method, Object[] args )
        {
            if ( Object.class.equals( method.getDeclaringClass() ) )
            {
                return "toString".equals( method.getName() ) ? "recording metadata store" : null;
            }
            if ( "getArtifactsByChecksum".equals( method.getName() ) )
            {
                storedRepoIds.add( (String) args[1] );
                return Collections.emptyList();
            }
            return defaultValue( method.getReturnType() );
        }
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
     * Stands in for the indexer, capturing the repository set each search was told to read.
     */
    private class RecordingSearch
        implements RepositorySearch
    {
        @Override
        public SearchResults search( String principal, List<String> selectedRepos, String term,
                                     SearchResultLimits limits, List<String> previousSearchTerms )
        {
            indexerCalls++;
            indexedRepoIds.addAll( selectedRepos == null ? Collections.<String>emptyList() : selectedRepos );
            return new SearchResults();
        }

        @Override
        public SearchResults search( String principal, SearchFields searchFields, SearchResultLimits limits )
            throws RepositorySearchException
        {
            indexerCalls++;
            List<String> repos = searchFields.getRepositories();
            indexedRepoIds.addAll( repos == null ? Collections.<String>emptyList() : repos );
            return new SearchResults();
        }

        @Override
        public Collection<String> getAllGroupIds( String principal, List<String> selectedRepos )
        {
            return Collections.emptyList();
        }

        @Override
        public Set<String> getRemoteIndexingContextIds( String managedRepoId )
        {
            return Collections.emptySet();
        }
    }

    private class TestableSearchService
        extends DefaultSearchService
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
