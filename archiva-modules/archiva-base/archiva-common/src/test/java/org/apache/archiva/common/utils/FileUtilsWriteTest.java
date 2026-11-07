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
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

import org.junit.Assume;
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
import java.util.stream.Stream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Tests for {@link FileUtils#writeStringToFile(Path, java.nio.charset.Charset, String)} and
 * {@link FileUtils#writeAtomically(Path, FileUtils.ContentWriter)}.
 * <p>
 * Both used to write in place and to log and swallow any {@link IOException}, so a failed write was
 * indistinguishable from a successful one for the caller, and a write that failed halfway left an
 * empty or truncated file behind. The tests below cover exactly those two properties: a failed write
 * is reported, and it does not damage what was there before.
 * </p>
 */
public class FileUtilsWriteTest
{
    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    @Test
    public void writeIsVisibleAndComplete()
        throws IOException
    {
        Path file = tmp.newFile().toPath();

        FileUtils.writeStringToFile( file, StandardCharsets.UTF_8, "first content" );

        assertEquals( "first content", Files.readString( file, StandardCharsets.UTF_8 ) );

        // a shorter replacement must not leave the tail of the previous content behind
        FileUtils.writeStringToFile( file, StandardCharsets.UTF_8, "second" );

        assertEquals( "second", Files.readString( file, StandardCharsets.UTF_8 ) );
        assertOnlyTheTargetRemains( file );
    }

    /**
     * The regression this whole change is about: an IOException while writing used to be logged only,
     * so callers continued as if the content had been stored.
     */
    @Test
    public void failedWriteIsReported()
        throws IOException
    {
        Path missingDir = tmp.getRoot().toPath().resolve( "no-such-directory" );
        Path file = missingDir.resolve( "maven-metadata.xml" );

        try
        {
            FileUtils.writeStringToFile( file, StandardCharsets.UTF_8, "content that never made it" );
            fail( "a write that cannot store anything must throw instead of returning quietly" );
        }
        catch ( IOException expected )
        {
            assertFalse( "nothing may be left where the target should be", Files.exists( file ) );
        }
    }

    /**
     * A write that fails after having written part of the content must leave the previous content
     * readable, which is what the rename over the target buys.
     */
    @Test
    public void failedWriteKeepsPreviousContent()
        throws IOException
    {
        Path file = tmp.newFile().toPath();
        FileUtils.writeStringToFile( file, StandardCharsets.UTF_8, "the good metadata" );

        try
        {
            FileUtils.writeAtomically( file, out -> {
                out.write( "partial".getBytes( StandardCharsets.UTF_8 ) );
                throw new IOException( "disk gave up" );
            } );
            fail( "the write failure must reach the caller" );
        }
        catch ( IOException expected )
        {
            assertEquals( "the target must still hold its previous content", "the good metadata",
                          Files.readString( file, StandardCharsets.UTF_8 ) );
            assertOnlyTheTargetRemains( file );
        }
    }

    /**
     * Same for a file that did not exist before: a failed write must not leave an empty file that
     * later reads look at as valid content.
     */
    @Test
    public void failedWriteDoesNotCreateTheTarget()
        throws IOException
    {
        Path file = tmp.newFolder().toPath().resolve( "artifact.jar.sha1" );

        try
        {
            FileUtils.writeAtomically( file, out -> {
                out.write( "0123456789".getBytes( StandardCharsets.UTF_8 ) );
                throw new IOException( "disk gave up" );
            } );
            fail( "the write failure must reach the caller" );
        }
        catch ( IOException expected )
        {
            assertFalse( "a failed write must not leave the target behind", Files.exists( file ) );
            try (Stream<Path> left = Files.list( file.getParent() ))
            {
                assertEquals( "a failed write must not leave temporary files behind", 0L, left.count() );
            }
        }
    }

    @Test
    public void replacingAFileKeepsItsPermissions()
        throws Exception
    {
        assumePosixFilesystem( tmp.getRoot().toPath() );

        Path file = tmp.newFile().toPath();
        Set<PosixFilePermission> requested = PosixFilePermissions.fromString( "rw-r-----" );
        Files.setPosixFilePermissions( file, requested );

        FileUtils.writeStringToFile( file, StandardCharsets.UTF_8, "replaced" );

        assertEquals( "replacing a file must not change its permissions", requested,
                      Files.getPosixFilePermissions( file ) );
    }

    @Test
    public void aNewFileIsReadableByItsOwner()
        throws Exception
    {
        assumePosixFilesystem( tmp.getRoot().toPath() );

        Path file = tmp.newFolder().toPath().resolve( "new-file.properties" );

        FileUtils.writeStringToFile( file, StandardCharsets.UTF_8, "new" );

        Set<PosixFilePermission> perms = Files.getPosixFilePermissions( file );
        assertTrue( "the owner must be able to read a file that was just written",
                    perms.contains( PosixFilePermission.OWNER_READ ) );
        assertTrue( "the owner must be able to write a file that was just written",
                    perms.contains( PosixFilePermission.OWNER_WRITE ) );
        assertFalse( "a written file must not be made executable",
                     perms.contains( PosixFilePermission.OWNER_EXECUTE ) );
    }

    /**
     * Nothing but the target may be left in the directory: a temporary file that outlives the write
     * would show up as an artifact in a repository listing.
     */
    private static void assertOnlyTheTargetRemains( Path file )
        throws IOException
    {
        try (Stream<Path> entries = Files.list( file.getParent() ))
        {
            assertEquals( "only the written file may remain in its directory", 1L, entries.count() );
        }
    }

    private static void assumePosixFilesystem( Path dir )
        throws IOException
    {
        FileStore store = Files.getFileStore( dir );
        Assume.assumeTrue( "posix permissions are not supported on " + store,
                           store.supportsFileAttributeView( "posix" ) );
    }
}
