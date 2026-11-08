package org.apache.archiva.metadata.repository.file;

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
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

import org.apache.archiva.configuration.model.Configuration;
import org.apache.archiva.configuration.model.ManagedRepositoryConfiguration;
import org.apache.archiva.configuration.provider.ArchivaConfiguration;
import org.apache.archiva.metadata.model.ArtifactMetadata;
import org.apache.archiva.metadata.model.MetadataFacet;
import org.apache.archiva.metadata.model.MetadataFacetFactory;
import org.apache.archiva.metadata.repository.MetadataRepositoryException;
import org.apache.archiva.metadata.repository.MetadataService;
import org.apache.archiva.metadata.repository.RepositorySession;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.ZonedDateTime;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * What {@link FileMetadataRepository} does when metadata cannot be stored.
 *
 * <p>This is the metadata store of a default installation, and it is where the records that outlive a scan are kept:
 * the audit trail of who deployed what, the problems a repository scan found, the statistics a scan collected. Both
 * {@code addMetadataFacet} and {@code updateArtifact} end in
 *
 * <pre>
 * } catch (IOException e) {
 *     // TODO!
 *     log.error(e.getMessage(), e);
 * }
 * </pre>
 *
 * <p>while their interface promises {@code @throws MetadataRepositoryException if the facet cannot be stored}. The
 * promise is the only channel there is: {@code DefaultAuditManager} logs the deployment as recorded,
 * {@code RepositoryProblemEventListener} tells the administrator the problem was filed, and
 * {@code DefaultRepositoryStatisticsManager.saveStatistics()} reports a scan that stored nothing. All three read as
 * success because the exception they would have been told about was written to a log line and dropped.
 *
 * <p>The failure is produced by the filesystem itself - a directory that cannot hold a new file - which is what a
 * full disk, a read-only mount or a backup job holding the metadata tree looks like from here. {@code writeProperties}
 * writes atomically, so the stored content of a failed update is expected to survive, and that is asserted too.
 *
 * @see <a href="https://github.com/alsutton/archiva/issues/77">issue #77</a>
 */
public class FileMetadataRepositoryWriteFailureTest
{
    private static final String REPO = "payroll";

    private static final String NAMESPACE = "org.payroll";

    private static final String PROJECT = "payroll-core";

    private static final String VERSION = "1.0";

    @TempDir
    Path repoRoot;

    private FileMetadataRepository repository;

    private RepositorySession session;

    @BeforeEach
    public void setUp()
        throws Exception
    {
        MetadataService metadataService = new MetadataService();
        metadataService.setMetadataFacetFactories( Collections.singletonList( new Notes.Factory() ) );

        repository = new FileMetadataRepository( metadataService, configuration() );
        session = new RepositorySession( repository, null );
    }

    /**
     * Puts the tree back into a state the test framework can remove. The tests take a directory out of service, and a
     * directory that cannot accept new entries cannot have the entries inside it deleted either, so without this the
     * temporary directory outlives the test and the failure reported is JUnit's, not the one under test.
     */
    @AfterEach
    public void makeTheStoredTreeDeletableAgain()
        throws IOException
    {
        List<Path> directories;
        try ( Stream<Path> paths = Files.walk( repoRoot ) )
        {
            directories = paths.filter( Files::isDirectory ).collect( Collectors.toList() );
        }
        for ( Path directory : directories )
        {
            Files.setPosixFilePermissions( directory, PosixFilePermissions.fromString( "rwxr-xr-x" ) );
        }
    }

    // ---------------------------------------------------------------------------------------------
    // the facets that hold audits and repository problems
    // ---------------------------------------------------------------------------------------------

    /**
     * A facet that could not be written has to come back as the exception the interface documents. Every caller of
     * this method treats a return as "stored", and the record it was keeping is the one nobody can recover: an audit
     * entry that never reached the store is a deployment with no trace of who made it.
     */
    @Test
    public void aFacetThatCouldNotBeStoredIsReportedToTheCaller()
        throws Exception
    {
        Path facetDir = store( new Notes( "deployed by payroll-clerk" ) );
        assumeTrue( cannotAddFilesTo( facetDir ), "the filesystem lets files be added to a read-only directory" );

        MetadataRepositoryException thrown = assertThrows( MetadataRepositoryException.class,
                                                           () -> repository.addMetadataFacet( session, REPO,
                                                                                              new Notes( "edited" ) ) );

        assertTrue( thrown.getMessage() != null && thrown.getMessage().contains( Notes.FACET_ID ),
                    "the failure did not say which facet could not be stored: " + thrown.getMessage() );
    }

    /**
     * What was stored before the failed update is still there. The properties are written to a temporary file that is
     * only moved into place once complete, so a store that cannot finish must leave the previous one readable rather
     * than empty - which is how this class lost metadata before, through a read error answered with fresh properties.
     */
    @Test
    public void aFacetThatCouldNotBeStoredLeavesTheStoredOneAlone()
        throws Exception
    {
        Path facetDir = store( new Notes( "deployed by payroll-clerk" ) );
        assumeTrue( cannotAddFilesTo( facetDir ), "the filesystem lets files be added to a read-only directory" );

        assertThrows( MetadataRepositoryException.class,
                      () -> repository.addMetadataFacet( session, REPO, new Notes( "edited" ) ) );

        assertEquals( "deployed by payroll-clerk",
                      repository.getMetadataFacet( session, REPO, Notes.class, Notes.NAME ).getValue() );
    }

    // ---------------------------------------------------------------------------------------------
    // the artifact records a scan leaves behind
    // ---------------------------------------------------------------------------------------------

    /**
     * The same for an artifact update, which is what {@code DefaultRepositoryStatisticsManager.saveStatistics()} and
     * the repository consumers use to record what a scan found. Without the exception, a scan whose metadata tree was
     * unwritable finishes and reports the repository as up to date.
     */
    @Test
    public void anArtifactThatCouldNotBeStoredIsReportedToTheCaller()
        throws Exception
    {
        ArtifactMetadata artifact = artifact();
        repository.updateArtifact( session, REPO, NAMESPACE, PROJECT, VERSION, artifact );

        Path versionDir = contentDir().resolve( NAMESPACE + "/" + PROJECT + "/" + VERSION );
        assumeTrue( cannotAddFilesTo( versionDir ), "the filesystem lets files be added to a read-only directory" );

        MetadataRepositoryException thrown =
            assertThrows( MetadataRepositoryException.class,
                          () -> repository.updateArtifact( session, REPO, NAMESPACE, PROJECT, VERSION, artifact ) );

        assertTrue( thrown.getMessage() != null && thrown.getMessage().contains( "payroll-core-1.0.jar" ),
                    "the failure did not say which artifact could not be stored: " + thrown.getMessage() );
    }

    // ---------------------------------------------------------------------------------------------
    // the stores that work, so that the tests above mean something
    // ---------------------------------------------------------------------------------------------

    /**
     * A facet that could be stored is stored: it comes back through the reader, so nothing above can be passing by
     * asserting on a store that never works.
     */
    @Test
    public void aFacetThatCouldBeStoredIsReadBack()
        throws Exception
    {
        store( new Notes( "deployed by payroll-clerk" ) );

        assertEquals( "deployed by payroll-clerk",
                      repository.getMetadataFacet( session, REPO, Notes.class, Notes.NAME ).getValue() );
    }

    /**
     * An artifact update that can be completed reaches the disk, and the file is not empty.
     */
    @Test
    public void anArtifactThatCouldBeStoredIsWritten()
        throws Exception
    {
        repository.updateArtifact( session, REPO, NAMESPACE, PROJECT, VERSION, artifact() );

        Path stored = contentDir().resolve( NAMESPACE + "/" + PROJECT + "/" + VERSION + "/version-metadata.properties" );
        assertTrue( Files.isReadable( stored ), "the artifact metadata was never written: " + stored );
        Properties read = new Properties();
        try ( Reader reader = Files.newBufferedReader( stored ) )
        {
            read.load( reader );
        }
        assertEquals( "1794067200000", read.getProperty( "artifact:updated:payroll-core-1.0.jar" ),
                      "the artifact metadata that was written does not describe the artifact: " + read );
    }

    // ---------------------------------------------------------------------------------------------
    // the pieces
    // ---------------------------------------------------------------------------------------------

    private Path store( Notes facet )
        throws Exception
    {
        repository.addMetadataFacet( session, REPO, facet );
        return repoRoot.resolve( REPO + "/.archiva/facets/" + Notes.FACET_ID + "/" + Notes.NAME );
    }

    private Path contentDir()
    {
        return repoRoot.resolve( REPO + "/.archiva/content" );
    }

    private static ArtifactMetadata artifact()
    {
        ArtifactMetadata artifact = new ArtifactMetadata();
        artifact.setId( "payroll-core-1.0.jar" );
        artifact.setRepositoryId( REPO );
        artifact.setNamespace( NAMESPACE );
        artifact.setProject( PROJECT );
        artifact.setProjectVersion( VERSION );
        artifact.setVersion( "1.0" );
        artifact.setFileLastModified( ZonedDateTime.parse( "2026-11-07T16:00:00Z" ).toInstant().toEpochMilli() );
        artifact.setWhenGathered( ZonedDateTime.parse( "2026-11-07T16:00:01Z" ) );
        artifact.setSize( 4096 );
        return artifact;
    }

    /**
     * Takes the directory out of service the way a full disk or a read-only mount does: entries can still be read,
     * nothing can be created or replaced inside it.
     */
    private static boolean cannotAddFilesTo( Path dir )
        throws IOException
    {
        FileStore store = Files.getFileStore( dir );
        if ( !store.supportsFileAttributeView( "posix" ) )
        {
            return false;
        }
        Files.setPosixFilePermissions( dir, PosixFilePermissions.fromString( "r-xr-xr-x" ) );
        return true;
    }

    private ArchivaConfiguration configuration()
    {
        ManagedRepositoryConfiguration managed = new ManagedRepositoryConfiguration();
        managed.setId( REPO );
        managed.setLocation( repoRoot.resolve( REPO ).toAbsolutePath().toString() );

        Configuration configData = new Configuration();
        configData.addManagedRepository( managed );

        ArchivaConfiguration config = mock( ArchivaConfiguration.class );
        when( config.getConfiguration() ).thenReturn( configData );
        return config;
    }

    /** A facet of the kind an audit event or a repository problem is stored as. */
    static final class Notes
        implements MetadataFacet
    {
        static final String FACET_ID = "archiva-repository-notes";

        static final String NAME = "notes";

        private String value;

        Notes( String value )
        {
            this.value = value;
        }

        public String getValue()
        {
            return value;
        }

        @Override
        public String getFacetId()
        {
            return FACET_ID;
        }

        @Override
        public String getName()
        {
            return NAME;
        }

        @Override
        public Map<String, String> toProperties()
        {
            Map<String, String> properties = new HashMap<>();
            properties.put( "note", value );
            return properties;
        }

        @Override
        public void fromProperties( Map<String, String> properties )
        {
            value = properties.get( "note" );
        }

        /** Makes the facet, which is what lets the reader read one back. */
        static final class Factory
            implements MetadataFacetFactory<Notes>
        {
            @Override
            public Notes createMetadataFacet()
            {
                return new Notes( null );
            }

            @Override
            public Notes createMetadataFacet( String repositoryId, String name )
            {
                return new Notes( null );
            }

            @Override
            public Class<Notes> getFacetClass()
            {
                return Notes.class;
            }

            @Override
            public String getFacetId()
            {
                return FACET_ID;
            }
        }
    }
}
