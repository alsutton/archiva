package org.apache.archiva.checksum;

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

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import static org.junit.Assert.assertEquals;

/**
 * A checksum file that could not be written has to fail the update.
 * <p>
 * Until recently the write failure was only logged, and {@code fixChecksums} went on to report the
 * digest as {@link UpdateStatus#CREATED} or {@link UpdateStatus#UPDATED}. Callers that trust that
 * result - {@code ChecksumPolicy} among them - then treat the artifact as verified while its digest
 * is missing or stale.
 * </p>
 */
public class ChecksummedFileWriteFailureTest
{
    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    @Test
    public void unwriteableChecksumFileFailsTheUpdate()
        throws IOException
    {
        Path artifact = tmp.newFolder( "repo" ).toPath().resolve( "artifact.jar" );
        Files.write( artifact, "the bytes the digest is about".getBytes( "UTF-8" ) );

        // something that can never be replaced by the digest file: the write of the digest fails
        Path checksumFile = artifact.resolveSibling( artifact.getFileName() + ".sha1" );
        Path occupant = Files.createDirectories( checksumFile.resolve( "occupied" ) );
        Files.write( occupant.resolve( "not-a-digest" ), new byte[0] );

        UpdateStatusList result = new ChecksummedFile( artifact )
            .fixChecksums( Arrays.asList( ChecksumAlgorithm.SHA1 ) );

        assertEquals( "a digest that could not be stored must fail the whole update", UpdateStatus.ERROR,
                      result.getTotalStatus() );
        assertEquals( "the algorithm must not be reported as created or updated", UpdateStatus.ERROR,
                      result.getStatus( ChecksumAlgorithm.SHA1 ).getValue() );
    }
}
