package org.apache.archiva.transaction;

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

import org.apache.archiva.test.utils.ArchivaBlockJUnit4ClassRunner;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;

import static org.junit.Assume.assumeTrue;

/**
 * Publishing content whose digests could not be stored leaves a repository holding a file that
 * every later reader believes is complete. {@code AbstractTransactionEvent.createChecksums} used to
 * discard the status that {@code ChecksummedFile.fixChecksums} returned, so the transaction had no
 * way to find out and committed anyway.
 *
 * <p>The test drives a real {@link FileTransaction} over two files: one that can be published
 * completely, and one whose digests cannot be created. The first must be rolled back when the
 * second fails - otherwise the transaction leaves the repository half updated, which is the
 * situation transactions exist to prevent.
 */
@RunWith( ArchivaBlockJUnit4ClassRunner.class )
public class ChecksumWriteFailureTransactionTest
    extends AbstractFileEventTest
{
    private static final String BASE = "transaction-tests/checksum-failure";

    @Test
    public void transactionRollsBackWhenAChecksumCannotBeWritten()
        throws Exception
    {
        Path base = Paths.get( org.apache.archiva.common.utils.FileUtils.getBasedir(), "target", BASE );
        Path blockedDir = base.resolve( "blocked" );
        Path writableDir = base.resolve( "writable" );

        deleteQuietly( base );
        Files.createDirectories( blockedDir );
        Files.createDirectories( writableDir );

        Path published = writableDir.resolve( "first-artifact.pom" );
        Path blocked = blockedDir.resolve( "second-artifact.pom" );

        // The destination already exists, so publishing it rewrites content that is there; only the
        // new digest needs create permission on the directory.
        writeFile( blocked, "<project><version>1</version></project>" );

        // r-x on the directory: the file inside it stays writable, nothing new can be created there.
        boolean posix = Files.getFileStore( blockedDir )
                                   .supportsFileAttributeView( PosixFileAttributeView.class );
        assumeTrue( "requires a filesystem with POSIX permissions to block the checksum write", posix );
        Files.setPosixFilePermissions( blockedDir, PosixFilePermissions.fromString( "r-xr-xr-x" ) );

        try
        {
            FileTransaction transaction = new FileTransaction();
            transaction.createFile( "<project><version>2</version></project>", published, checksumAlgorithms );
            transaction.createFile( "<project><version>2</version></project>", blocked, checksumAlgorithms );

            try
            {
                transaction.commit();
                fail( "a checksum that could not be stored must fail the transaction" );
            }
            catch ( TransactionException expected )
            {
                // the digests of the second file could not be written, which is what this is about
            }

            assertFalse( "the file published before the failure must be rolled back", Files.exists( published ) );
            assertFalse( "no digest may be claimed for the file that failed",
                         Files.exists( Paths.get( blocked.toAbsolutePath() + ".sha1" ) ) );
        }
        finally
        {
            Files.setPosixFilePermissions( blockedDir, PosixFilePermissions.fromString( "rwxr-xr-x" ) );
            deleteQuietly( base );
        }
    }

    /**
     * The blocked directory cannot have its contents deleted while it is read-only, hence the mode
     * being restored before this runs.
     */
    private void deleteQuietly( Path path )
        throws IOException
    {
        if ( java.nio.file.Files.isDirectory( path ) )
        {
            Files.setPosixFilePermissions( path, PosixFilePermissions.fromString( "rwxr-xr-x" ) );
            try ( var children = Files.walk( path ) )
            {
                for ( Path child : children.sorted( ( a, b ) -> b.getNameCount() - a.getNameCount() ).toList() )
                {
                    if ( !child.equals( path ) )
                    {
                        Files.setPosixFilePermissions( child, PosixFilePermissions.fromString( "rw-r--r--" ) );
                    }
                }
            }
        }
        org.apache.commons.io.FileUtils.deleteQuietly( path.toFile() );
    }
}
