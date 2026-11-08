package org.apache.archiva.rss.processor;

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

import com.rometools.rome.feed.synd.SyndFeed;
import org.apache.archiva.metadata.model.ArtifactMetadata;
import org.apache.archiva.metadata.repository.MetadataRepository;
import org.apache.archiva.metadata.repository.RepositorySession;
import org.apache.archiva.metadata.repository.RepositorySessionFactory;
import org.apache.archiva.repository.Repository;
import org.apache.archiva.repository.RepositoryRegistry;
import org.apache.archiva.rss.RssFeedGenerator;
import org.junit.Before;
import org.junit.Test;

import java.lang.reflect.Proxy;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

/**
 * The "new versions of artifact" feed spans every repository, so it is the one RSS feed whose scope cannot be
 * decided by the repository id in the URL. It used to walk {@code RepositoryRegistry.getRepositories()} - every
 * repository in the instance, including private ones - and publish the versions it found to whoever asked, which
 * the servlet's own comment admitted: {@code // TODO: this only works for guest - we could pass in the list of repos}.
 *
 * <p>The processor now builds the feed from the repositories the caller was granted, handed over as
 * {@link RssFeedProcessor#KEY_REPO_IDS}. These tests pin that the processor reads those and only those, and that an
 * absent list means nothing was authorised rather than everything.
 *
 * @see <a href="https://github.com/alsutton/archiva/issues/66">issue #66</a>
 */
public class NewVersionsOfArtifactRssFeedProcessorRepoScopeTest
{
    private static final String GROUP_ID = "com.example.payroll";

    private static final String ARTIFACT_ID = "payroll-engine";

    private static final String RELEASES = "releases";

    private static final String PAYROLL = "payroll-internal";

    /** Repositories the metadata store was asked about, in call order. */
    private final List<String> queriedRepos = new ArrayList<>();

    private NewVersionsOfArtifactRssFeedProcessor processor;

    @Before
    public void setUp()
        throws Exception
    {
        processor = new NewVersionsOfArtifactRssFeedProcessor();
        processor.setGenerator( new RssFeedGenerator() );
        processor.setRepositorySessionFactory( metadataSessionFactory() );
        // every repository in the instance, the set the processor used to walk
        processor.setRepositoryRegistry( repositoryRegistry( RELEASES, PAYROLL ) );
    }

    /**
     * The disclosure: the feed read every repository it could find. With the permitted repositories supplied, a
     * repository outside that list must not be queried at all.
     */
    @Test
    public void theFeedIsBuiltFromThePermittedRepositoriesOnly()
        throws Exception
    {
        SyndFeed feed = processor.process( request( RELEASES ) );

        assertNotNull( feed );
        assertEquals( "the private repository must never be queried", Collections.singletonList( RELEASES ),
                      queriedRepos );
    }

    /**
     * Several permitted repositories are all fair game, and each is asked exactly once.
     */
    @Test
    public void everyPermittedRepositoryIsRead()
        throws Exception
    {
        processor.process( request( RELEASES + "," + PAYROLL ) );

        assertEquals( Arrays.asList( RELEASES, PAYROLL ), queriedRepos );
    }

    /**
     * The list arrives as a URL-ish parameter, so stray whitespace and repeats must not turn into phantom
     * repositories or duplicate reads.
     */
    @Test
    public void thePermittedListIsTrimmedAndDeduplicated()
        throws Exception
    {
        processor.process( request( " " + RELEASES + " , ," + RELEASES + ",, " ) );

        assertEquals( Collections.singletonList( RELEASES ), queriedRepos );
    }

    /**
     * A processor handed nothing has not been authorised to read anything. Defaulting to the registry here is what
     * leaked, so "no list" has to mean "no repositories".
     */
    @Test
    public void aRequestWithNoPermittedRepositoriesReadsNothing()
        throws Exception
    {
        SyndFeed feed = processor.process( request( null ) );

        assertNull( "with nothing readable there is nothing to feed, which the servlet answers as 204", feed );
        assertEquals( Collections.emptyList(), queriedRepos );
    }

    @Test
    public void aBlankPermittedRepositoriesListReadsNothing()
        throws Exception
    {
        processor.process( request( "  " ) );

        assertEquals( Collections.emptyList(), queriedRepos );
    }

    // ----------------------------------------------------------------------------------------------
    // fixtures
    // ----------------------------------------------------------------------------------------------

    private Map<String, String> request( String repoIds )
    {
        Map<String, String> reqParams = new HashMap<>();
        reqParams.put( RssFeedProcessor.KEY_GROUP_ID, GROUP_ID );
        reqParams.put( RssFeedProcessor.KEY_ARTIFACT_ID, ARTIFACT_ID );
        if ( repoIds != null )
        {
            reqParams.put( RssFeedProcessor.KEY_REPO_IDS, repoIds );
        }
        return reqParams;
    }

    /**
     * A metadata store that answers for every repository that exists and records which ones it was asked about.
     * Answering for all of them is what makes "only the permitted ones were queried" observable.
     */
    private RepositorySessionFactory metadataSessionFactory()
    {
        MetadataRepository metadataRepository =
            (MetadataRepository) Proxy.newProxyInstance( getClass().getClassLoader(),
                                                         new Class<?>[] { MetadataRepository.class },
                                                         ( proxy, method, args ) -> {
                                                             if ( Object.class.equals(
                                                                 method.getDeclaringClass() ) )
                                                             {
                                                                 return "recording metadata store";
                                                             }
                                                             if ( "getProjectVersions".equals( method.getName() ) )
                                                             {
                                                                 String repoId = (String) args[1];
                                                                 queriedRepos.add( repoId );
                                                                 return Collections.singletonList( "1.0" );
                                                             }
                                                             if ( "getArtifacts".equals( method.getName() ) )
                                                             {
                                                                 return Collections.singletonList(
                                                                     artifact( (String) args[1] ) );
                                                             }
                                                             return defaultValue( method.getReturnType() );
                                                         } );
        // RepositorySession is a concrete class whose close() only saves a dirty session.
        RepositorySession session = new RepositorySession( metadataRepository, null );

        return (RepositorySessionFactory) Proxy.newProxyInstance( getClass().getClassLoader(),
                                                                  new Class<?>[] {
                                                                      RepositorySessionFactory.class },
                                                                  ( proxy, method, args ) -> "createSession".equals(
                                                                      method.getName() ) ? session : defaultValue(
                                                                      method.getReturnType() ) );
    }

    /**
     * The repositories that exist in the instance. Deliberately includes the private one: the processor used to
     * read all of these, and still must not.
     */
    private RepositoryRegistry repositoryRegistry( String... repoIds )
    {
        List<Repository> repositories = new ArrayList<>();
        for ( final String repoId : repoIds )
        {
            repositories.add( (Repository) Proxy.newProxyInstance( getClass().getClassLoader(),
                                                                   new Class<?>[] { Repository.class },
                                                                   ( proxy, method, args ) -> "getId".equals(
                                                                       method.getName() ) ? repoId : defaultValue(
                                                                       method.getReturnType() ) ) );
        }

        return (RepositoryRegistry) Proxy.newProxyInstance( getClass().getClassLoader(),
                                                            new Class<?>[] { RepositoryRegistry.class },
                                                            ( proxy, method, args ) -> "getRepositories".equals(
                                                                method.getName() ) ? repositories : defaultValue(
                                                                method.getReturnType() ) );
    }

    private ArtifactMetadata artifact( String repoId )
    {
        ArtifactMetadata artifact = new ArtifactMetadata();
        artifact.setRepositoryId( repoId );
        artifact.setNamespace( GROUP_ID );
        artifact.setProject( ARTIFACT_ID );
        artifact.setProjectVersion( "1.0" );
        artifact.setVersion( "1.0" );
        artifact.setWhenGathered(
            ZonedDateTime.ofInstant( new Date( 123456789L ).toInstant(), ZoneId.systemDefault() ) );
        return artifact;
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
        return null;
    }
}
