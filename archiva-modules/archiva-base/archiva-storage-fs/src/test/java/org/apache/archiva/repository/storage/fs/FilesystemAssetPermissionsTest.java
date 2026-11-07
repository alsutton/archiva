package org.apache.archiva.repository.storage.fs;

/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

import org.apache.archiva.common.filelock.DefaultFileLockManager;
import org.apache.archiva.repository.storage.AssetType;
import org.apache.commons.io.FileUtils;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Checks that {@link FilesystemAsset} applies the directory permission set to containers and the
 * file permission set to files.
 *
 * <p>The two sets used to be swapped on the POSIX branch of
 * {@code FilesystemAsset.applyDefaultPermissions}. {@code DEFAULT_POSIX_FILE_PERMS} is
 * {@code rw-rw----}, which carries no execute bit, and a POSIX directory without an execute bit
 * cannot be traversed at all - not even by its owner. The observable failure is that a freshly
 * created container rejects every entry created underneath it, while stored artifacts picked up
 * {@code rwxrwx---} and became executables.
 *
 * <p>Written as a Jupiter test on purpose: the JUnit 4 vintage engine on this module's classpath
 * fails to parse {@code junit:junit:4.13.2} and silently discovers nothing, so Jupiter 4-style
 * tests in this package report success without running.
 */
public class FilesystemAssetPermissionsTest
{
    @Test
    public void newContainerGetsTraversableDirectoryPermissions() throws IOException
    {
        assumePosixFilesystem();
        Path baseDir = Files.createTempDirectory( "archivaPermsContainer" );
        try
        {
            FilesystemStorage storage = new FilesystemStorage( baseDir, new DefaultFileLockManager() );
            FilesystemAsset container =
                new FilesystemAsset( storage, "/newDir", baseDir.resolve( "newDir" ), baseDir, true, true );

            container.create( AssetType.CONTAINER );

            String perms = PosixFilePermissions.toString( Files.getPosixFilePermissions( container.getFilePath() ) );
            assertEquals( FilesystemAsset.DEFAULT_POSIX_DIR_PERMS, perms,
                          "a newly created container must receive the directory permissions" );

            // The functional property that broke: an entry must be creatable inside the container.
            Path child = container.getFilePath().resolve( "artifact.jar" );
            Files.createFile( child );
            assertTrue( Files.exists( child ),
                        "a directory that rejects its own entries is unusable, perms were " + perms );
        }
        finally
        {
            FileUtils.deleteQuietly( baseDir.toFile() );
        }
    }

    @Test
    public void newFileGetsNonExecutableFilePermissions() throws IOException
    {
        assumePosixFilesystem();
        Path baseDir = Files.createTempDirectory( "archivaPermsFile" );
        try
        {
            FilesystemStorage storage = new FilesystemStorage( baseDir, new DefaultFileLockManager() );
            FilesystemAsset file =
                new FilesystemAsset( storage, "/artifact.jar", baseDir.resolve( "artifact.jar" ), baseDir, false,
                                      true );

            file.create( AssetType.FILE );

            Set<PosixFilePermission> perms = Files.getPosixFilePermissions( file.getFilePath() );
            String permsString = PosixFilePermissions.toString( perms );
            assertEquals( FilesystemAsset.DEFAULT_POSIX_FILE_PERMS, permsString,
                          "a newly created artifact must receive the file permissions" );
            // GROUP_WRITE is part of the project's chosen default (rw-rw----), so it is not
            // asserted here. The execute bit is: turning stored artifacts into executables was the
            // regression, and the exact mode above is pinned by the assertion before this one.
            assertFalse( perms.contains( PosixFilePermission.OWNER_EXECUTE ),
                         "stored artifacts must not be executable: " + permsString );
        }
        finally
        {
            FileUtils.deleteQuietly( baseDir.toFile() );
        }
    }

    /**
     * The layout Archiva actually stores is a nest of containers - repository root, group,
     * artifact, version - and every level of it is created through this class. A single level
     * without a traverse bit makes everything below it unreachable, so a container has to be able
     * to hold another container that was itself created by the code under test.
     */
    @Test
    public void containersCanBeNestedAsDeepAsARepositoryNeeds() throws IOException
    {
        assumePosixFilesystem();
        Path baseDir = Files.createTempDirectory( "archivaPermsNest" );
        try
        {
            FilesystemStorage storage = new FilesystemStorage( baseDir, new DefaultFileLockManager() );

            createContainer( storage, baseDir, "repo" );
            createContainer( storage, baseDir, "repo/commons-io" );
            FilesystemAsset versionDir = createContainer( storage, baseDir, "repo/commons-io/2.11.0" );

            Path artifact = versionDir.getFilePath().resolve( "commons-io-2.11.0.jar" );
            Files.createFile( artifact );
            assertTrue( Files.exists( artifact ), "an artifact could not be stored in its version directory" );
        }
        finally
        {
            FileUtils.deleteQuietly( baseDir.toFile() );
        }
    }

    /**
     * {@code replaceDataFromFile} is how a staged upload is promoted into its final place, and it
     * re-applies the default permissions to whatever landed there. It has to hand back the bytes
     * that were staged, and an artifact that is not an executable.
     */
    @Test
    public void replacingAnAssetsDataKeepsItReadableAndNonExecutable() throws IOException
    {
        assumePosixFilesystem();
        Path baseDir = Files.createTempDirectory( "archivaPermsReplace" );
        try
        {
            // Built with plain NIO: this test is about the permissions replaceDataFromFile applies,
            // not about container creation.
            Files.createDirectories( baseDir.resolve( "repo" ) );

            FilesystemStorage storage = new FilesystemStorage( baseDir, new DefaultFileLockManager() );
            FilesystemAsset asset =
                new FilesystemAsset( storage, "/repo/artifact.jar", baseDir.resolve( "repo/artifact.jar" ), baseDir,
                                      false, true );

            Path staged = baseDir.resolve( "staged.jar" );
            Files.writeString( staged, "the staged artifact bytes" );

            assertTrue( asset.replaceDataFromFile( staged ), "the staged upload was not promoted" );

            Set<PosixFilePermission> perms = Files.getPosixFilePermissions( asset.getFilePath() );
            String permsString = PosixFilePermissions.toString( perms );
            assertEquals( FilesystemAsset.DEFAULT_POSIX_FILE_PERMS, permsString,
                          "a promoted artifact must carry the file permissions" );
            assertFalse( perms.contains( PosixFilePermission.OWNER_EXECUTE ),
                         "a promoted artifact must not be executable: " + permsString );
            assertEquals( "the staged artifact bytes", Files.readString( asset.getFilePath() ),
                          "promoting an upload must not change the artifact" );
        }
        finally
        {
            FileUtils.deleteQuietly( baseDir.toFile() );
        }
    }

    /**
     * Creates a container the way the storage layer does and pins the mode it ended up with, so
     * the first level of a nest that cannot be traversed names itself in the failure.
     */
    private static FilesystemAsset createContainer( FilesystemStorage storage, Path baseDir, String relativePath )
        throws IOException
    {
        FilesystemAsset container =
            new FilesystemAsset( storage, "/" + relativePath, baseDir.resolve( relativePath ), baseDir, true, true );

        container.create( AssetType.CONTAINER );

        String perms = PosixFilePermissions.toString( Files.getPosixFilePermissions( container.getFilePath() ) );
        assertEquals( FilesystemAsset.DEFAULT_POSIX_DIR_PERMS, perms,
                      container.getPath() + " was created as " + perms + ", which cannot be traversed" );

        return container;
    }

    private static void assumePosixFilesystem() throws IOException
    {
        Path root = Paths.get( "." ).toAbsolutePath().getRoot();
        assumeTrue( root != null && Files.getFileStore( root )
                                        .supportsFileAttributeView( PosixFileAttributeView.class ),
                    "requires a filesystem that supports POSIX file permissions" );
    }
}
