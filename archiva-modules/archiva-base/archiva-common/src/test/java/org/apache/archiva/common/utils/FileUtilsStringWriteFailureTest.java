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
import java.nio.file.attribute.PosixFilePermissions;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assume.assumeTrue;

/**
 * {@code writeStringToFile} used to catch the {@link IOException}, log it without the exception, and
 * return. Every caller - checksum files, metadata files, transaction content - therefore treated a
 * write that never happened as one that did.
 *
 * <p>This class deliberately reaches for nothing but {@code writeStringToFile}: no
 * {@code writeAtomically}, no {@code ContentWriter}. That keeps it compiling against the version
 * that swallowed the failure, so it fails there instead of failing to build - which is the only way
 * a test can demonstrate a bug it is guarding against.
 *
 * <p>The behaviour of the atomic write itself is in {@link FileUtilsWriteTest}.
 */
public class FileUtilsStringWriteFailureTest
{
    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    @Test
    public void aWriteThatCouldNotBeCompletedIsReportedToTheCaller()
        throws IOException
    {
        Path dir = tmp.newFolder( "read-only" ).toPath();
        assumePosixPermissions( dir );

        Path file = dir.resolve( "metadata.properties" );
        FileUtils.writeStringToFile( file, StandardCharsets.UTF_8, "the metadata already stored" );

        // r-x: the file inside stays readable and writable, but nothing can be created or replaced
        // in the directory - which is all that storing new content needs.
        Files.setPosixFilePermissions( dir, PosixFilePermissions.fromString( "r-xr-xr-x" ) );
        try
        {
            assertThrows( "a write that never reached the disk must not return quietly", IOException.class,
                          () -> FileUtils.writeStringToFile( file, StandardCharsets.UTF_8, "replacement" ) );

            assertEquals( "a write that failed must not damage what was already stored",
                          "the metadata already stored", Files.readString( file, StandardCharsets.UTF_8 ) );
        }
        finally
        {
            Files.setPosixFilePermissions( dir, PosixFilePermissions.fromString( "rwxr-xr-x" ) );
        }
    }

    /**
     * And the same directory in state that allows the write, so the test above cannot pass by
     * asserting a permission that nothing in the code under test honours.
     */
    @Test
    public void aWriteThatCanBeCompletedStillSucceeds()
        throws IOException
    {
        Path dir = tmp.newFolder( "writable" ).toPath();
        Path file = dir.resolve( "metadata.properties" );

        FileUtils.writeStringToFile( file, StandardCharsets.UTF_8, "the metadata already stored" );

        assertEquals( "the metadata already stored", Files.readString( file, StandardCharsets.UTF_8 ) );
    }

    private static void assumePosixPermissions( Path dir )
        throws IOException
    {
        FileStore store = Files.getFileStore( dir );
        assumeTrue( "posix permissions are not supported on " + store, store.supportsFileAttributeView( "posix" ) );
    }
}
