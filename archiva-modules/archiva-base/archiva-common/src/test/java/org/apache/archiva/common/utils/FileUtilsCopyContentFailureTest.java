package org.apache.archiva.common.utils;

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

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

/**
 * What {@link FileUtils#copyContent} does with a copy that could not be made.
 *
 * <p>The one thing that decides whether this method has to report anything is what its caller does next. Its only
 * production caller is the index move in {@code MavenIndexManager}:
 *
 * <pre>
 * FileUtils.copyContent( oldPath, newFilePath );   // a copy that half failed returns quietly
 * FileUtils.deleteDirectory( oldPath );            // and the source of that copy is deleted anyway
 * </pre>
 *
 * <p>So a silent failure is not merely an unreported error, it is the destruction of the good copy: one
 * {@code ENOSPC} or {@code EACCES} on a single Lucene segment leaves the new index short of that segment and the old
 * one gone, while the move reports success - and recovering that index means re-indexing the repository. The method
 * has to answer the same way {@code deleteDirectory} on the very next line already does, which throws.
 *
 * <p>The failure is produced by the filesystem: a segment with no read bit, so the copy of that one file cannot be
 * opened by anybody, which is the case the caller cannot survive. The readable tree beside it is the control, and it
 * keeps the hostile test from passing because nothing is ever copied.
 */
public class FileUtilsCopyContentFailureTest
{
    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    /** The index that is being moved, as the caller has it. */
    private Path indexDir;

    /** Where the caller is moving it to, already created. */
    private Path newIndexDir;

    // ---------------------------------------------------------------------------------------------
    // a copy that cannot be completed
    // ---------------------------------------------------------------------------------------------

    /**
     * A tree where one file cannot be read. The copy of everything else may well succeed, and the caller must not
     * learn that from the return: this is the moment it decides whether the source can be deleted.
     */
    @Test
    public void aCopyThatCouldNotBeCompletedIsReportedToTheCaller()
        throws Exception
    {
        setUpIndex();
        assumeTrue( "the filesystem lets a file with no read bit be opened", unreadable( segment( "shard-2.cfs" ) ) );

        try
        {
            IOException notCopied =
                assertThrows( "a tree that was only partly copied was reported as copied", IOException.class,
                              () -> FileUtils.copyContent( indexDir, newIndexDir ) );

            assertTrue( "the failure does not name the file that could not be copied: " + notCopied.getMessage(),
                        notCopied.getMessage().contains( "shard-2.cfs" ) );
        }
        finally
        {
            readableAgain( segment( "shard-2.cfs" ) );
        }
    }

    /**
     * The source is what the caller deletes on the strength of this method returning, so the copy that failed leaves
     * it exactly as it was: a failed copy must not cost the caller the data it was copying from.
     */
    @Test
    public void aCopyThatCouldNotBeCompletedLeavesTheSourceAlone()
        throws Exception
    {
        setUpIndex();
        assumeTrue( "the filesystem lets a file with no read bit be opened", unreadable( segment( "shard-2.cfs" ) ) );

        try
        {
            assertThrows( IOException.class, () -> FileUtils.copyContent( indexDir, newIndexDir ) );

            assertEquals( "segments_2", Files.readString( indexDir.resolve( "segments_2" ), StandardCharsets.UTF_8 ) );
            assertTrue( "the file the caller could not copy has been lost from the source",
                        Files.exists( segment( "shard-2.cfs" ) ) );
        }
        finally
        {
            readableAgain( segment( "shard-2.cfs" ) );
        }

        assertEquals( "the segment that could not be read has lost its content", "the second segment",
                      Files.readString( segment( "shard-2.cfs" ), StandardCharsets.UTF_8 ) );
    }

    // ---------------------------------------------------------------------------------------------
    // the control
    // ---------------------------------------------------------------------------------------------

    /**
     * An index that can be read is copied whole, subdirectories and all, and stays where it was until the caller has
     * decided what to do with it.
     */
    @Test
    public void aTreeThatCanBeReadIsCopiedInFull()
        throws Exception
    {
        setUpIndex();

        FileUtils.copyContent( indexDir, newIndexDir );

        assertEquals( "segments_2",
                      Files.readString( newIndexDir.resolve( "segments_2" ), StandardCharsets.UTF_8 ) );
        assertEquals( "the second segment",
                      Files.readString( newIndexDir.resolve( "nested/shard-2.cfs" ), StandardCharsets.UTF_8 ) );
        assertTrue( "the copy went over the source instead of beside it", Files.exists( segment( "shard-2.cfs" ) ) );
    }

    /** A copy with nowhere to go is not a copy either: the destination has to exist for the caller to trust it. */
    @Test
    public void aTreeCopiedToAFreshDirectoryEndsUpInTheRightPlace()
        throws Exception
    {
        indexDir = tmp.newFolder( "index-old" ).toPath();
        Files.write( indexDir.resolve( "segments_2" ), "segments".getBytes( StandardCharsets.UTF_8 ) );
        Files.createDirectories( indexDir.resolve( "nested" ) );
        Files.write( indexDir.resolve( "nested/shard-2.cfs" ), "the second segment".getBytes( StandardCharsets.UTF_8 ) );
        newIndexDir = tmp.newFolder( "index-new" ).toPath();
        FileUtils.deleteDirectory( newIndexDir );

        FileUtils.copyContent( indexDir, newIndexDir );

        assertEquals( "the second segment",
                      Files.readString( newIndexDir.resolve( "nested/shard-2.cfs" ), StandardCharsets.UTF_8 ) );
    }

    // ---------------------------------------------------------------------------------------------
    // the pieces
    // ---------------------------------------------------------------------------------------------

    private void setUpIndex()
        throws IOException
    {
        indexDir = tmp.newFolder( "index" ).toPath();
        newIndexDir = tmp.newFolder( "index-moving-to" ).toPath();

        Files.write( indexDir.resolve( "segments_2" ), "segments_2".getBytes( StandardCharsets.UTF_8 ) );
        Files.createDirectories( indexDir.resolve( "nested" ) );
        Files.write( segment( "shard-2.cfs" ), "the second segment".getBytes( StandardCharsets.UTF_8 ) );
    }

    private Path segment( String name )
    {
        return indexDir.resolve( "nested" ).resolve( name );
    }

    /**
     * Takes every bit off one file, the way a segment nobody can read looks.
     *
     * @return whether the filesystem honoured it, which is the premise of the test asking
     */
    private static boolean unreadable( Path file )
        throws IOException
    {
        FileStore store = Files.getFileStore( file.getParent() );
        if ( !store.supportsFileAttributeView( "posix" ) )
        {
            return false;
        }
        Set<PosixFilePermission> none = PosixFilePermissions.fromString( "---------" );
        Files.setPosixFilePermissions( file, none );
        try
        {
            Files.newInputStream( file ).close();
            return false;
        }
        catch ( IOException expected )
        {
            return true;
        }
    }

    private static void readableAgain( Path file )
        throws IOException
    {
        Files.setPosixFilePermissions( file, PosixFilePermissions.fromString( "rw-r--r--" ) );
    }
}
