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
 *   http://www.apache.org/licenses/LICENSE-2.0
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
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * What {@link FilesystemAsset} does when the mode it wants for an entry cannot be applied.
 *
 * <p>{@code applyDefaultPermissions} is the last thing that happens to an upload: the bytes have been moved to their
 * final path, are visible to every reader, and then the mode the storage promised is set on them. The call used to end
 * in
 *
 * <pre>
 * } catch (IOException e) {
 *     log.error("Could not set permissions for {}: {}", filePath, e.getMessage());
 * }
 * </pre>
 *
 * <p>so {@code replaceDataFromFile} went on to return {@code true} and {@code create()} went on to return quietly. A
 * deployment over WebDAV or a Maven client answered 201 for an artifact that its own group could not read: the
 * standard installation serves and consumes artifacts as a different user than the webapp runs as, which is exactly
 * when the mode is doing work, and the next request for the path is a 403 or a {@code Permission denied} on the
 * upload that would have replaced it. Nothing in the request said so, because the only thing that could has been
 * written to a log line and dropped.
 *
 * <p>The failure is real but cannot be conjured from outside: whatever stops {@code setPosixFilePermissions} stops
 * the write that publishes the entry before it, since both act on the same inode, so no filesystem state leaves a
 * file publishable and unchmodable. It is injected at the call the swallow was in - the exception is the one a
 * filesystem produces for exactly that case, the {@code EPERM} an NFS mount with root-squash answers a
 * {@code chmod}, or a file whose owner is somebody else.
 *
 * @see <a href="https://github.com/alsutton/archiva/issues/78">issue #78</a>
 */
public class FilesystemAssetPermissionFailureTest
{
    private static final String STORED_BYTES = "the artifact that is already stored";

    private static final String STAGED_BYTES = "the artifact being uploaded";

    // ---------------------------------------------------------------------------------------------
    // the upload of a replacement, which is what a deployment is
    // ---------------------------------------------------------------------------------------------

    /**
     * An upload whose mode could not be applied is not an upload that succeeded. Before this, the caller of
     * {@code replaceDataFromFile} - the WebDAV PUT, the Maven-facing repository layer - had {@code true} in hand and
     * nothing left to report.
     */
    @Test
    public void anUploadWhosePermissionsCouldNotBeAppliedIsReportedToTheUploader()
        throws IOException
    {
        assumePosixFilesystem();
        Path baseDir = Files.createTempDirectory( "archivaPermFailUpload" );
        try
        {
            Files.createDirectories( baseDir.resolve( "repo" ) );
            Path stored = baseDir.resolve( "repo/artifact.jar" );
            Files.writeString( stored, STORED_BYTES );

            PermissionFailingAsset asset = asset( baseDir, "repo/artifact.jar", true );
            Path staged = stage( baseDir );

            AccessDeniedException thrown =
                assertThrows( AccessDeniedException.class, () -> asset.replaceDataFromFile( staged ) );

            assertTrue( thrown.getMessage() != null && thrown.getMessage().contains( "artifact.jar" ),
                        "the failure did not say which entry it could not be applied to: " + thrown.getMessage() );
        }
        finally
        {
            FileUtils.deleteQuietly( baseDir.toFile() );
        }
    }

    /**
     * What was stored before the failed upload is stored afterwards. The replacement lands at its final path before
     * its mode can be applied, so an entry nobody could read is already live by the time the failure is known, and
     * leaving it there would replace an artifact that worked with one that does not. The method's own revert is the
     * only thing standing between a failed permission change and a repository whose path serves nothing.
     */
    @Test
    public void anUploadWhosePermissionsCouldNotBeAppliedDoesNotReplaceTheStoredArtifact()
        throws IOException
    {
        assumePosixFilesystem();
        Path baseDir = Files.createTempDirectory( "archivaPermFailRevert" );
        try
        {
            Files.createDirectories( baseDir.resolve( "repo" ) );
            Path stored = baseDir.resolve( "repo/artifact.jar" );
            Files.writeString( stored, STORED_BYTES );

            PermissionFailingAsset asset = asset( baseDir, "repo/artifact.jar", true );
            Path staged = stage( baseDir );

            assertThrows( IOException.class, () -> asset.replaceDataFromFile( staged ) );

            assertEquals( STORED_BYTES, Files.readString( asset.getFilePath() ),
                          "the artifact that used to work was replaced by one nobody can read" );
            assertTrue( Files.exists( asset.getFilePath() ), "the failed upload left the artifact missing" );
        }
        finally
        {
            FileUtils.deleteQuietly( baseDir.toFile() );
        }
    }

    // ---------------------------------------------------------------------------------------------
    // creating an entry, which is where every container and every metadata file begins
    // ---------------------------------------------------------------------------------------------

    /**
     * The same for the two {@code create()} methods, which publish the entry and then set its mode. A container
     * created without its mode is a directory whose contents are unreachable, and every container in a repository
     * layout is created through here.
     */
    @Test
    public void anEntryCreatedWhosePermissionsCouldNotBeAppliedIsReported()
        throws IOException
    {
        assumePosixFilesystem();
        Path baseDir = Files.createTempDirectory( "archivaPermFailCreate" );
        try
        {
            Files.createDirectories( baseDir.resolve( "repo" ) );

            PermissionFailingAsset asset = asset( baseDir, "repo/artifact.jar", true );

            assertThrows( AccessDeniedException.class, asset::create );

            PermissionFailingAsset other = asset( baseDir, "repo/other.jar", true );

            assertThrows( AccessDeniedException.class, () -> other.create( AssetType.FILE ) );
        }
        finally
        {
            FileUtils.deleteQuietly( baseDir.toFile() );
        }
    }

    /**
     * A failed create leaves no entry behind, which is what makes the report outlast the call. The entry is created
     * before its mode, so an entry left here is an entry whose mode is wrong and whose path is taken: the next
     * {@code create()} finds something existing and returns quietly, and every reader of that path gets the mode the
     * filesystem happened to give it. {@code StorageAsset.create} is idempotent only for an entry that made it.
     */
    @Test
    public void anEntryWhosePermissionsCouldNotBeAppliedLeavesNothingBehind()
        throws IOException
    {
        assumePosixFilesystem();
        Path baseDir = Files.createTempDirectory( "archivaPermFailCreateCleanup" );
        try
        {
            Files.createDirectories( baseDir.resolve( "repo" ) );

            PermissionFailingAsset asset = asset( baseDir, "repo/artifact.jar", true );

            assertThrows( AccessDeniedException.class, asset::create );
            assertFalse( Files.exists( asset.getFilePath() ),
                         "a create that reported a failure left the entry it could not finish at its path" );

            // And the retry is told as well, rather than finding the leftover and calling it done.
            assertThrows( AccessDeniedException.class, asset::create );
        }
        finally
        {
            FileUtils.deleteQuietly( baseDir.toFile() );
        }
    }

    /**
     * A container whose mode could not be applied is reported too, since a directory is the entry every other entry
     * in the layout lives under, and the one created without its mode is a subtree nothing outside the webapp user
     * can reach.
     */
    @Test
    public void aContainerCreatedWhosePermissionsCouldNotBeAppliedIsReported()
        throws IOException
    {
        assumePosixFilesystem();
        Path baseDir = Files.createTempDirectory( "archivaPermFailCreateContainer" );
        try
        {
            PermissionFailingAsset container = new PermissionFailingAsset(
                new FilesystemStorage( baseDir, new DefaultFileLockManager() ), "/repo/org/example",
                baseDir.resolve( "repo/org/example" ), baseDir, true, true, true );

            assertThrows( AccessDeniedException.class, () -> container.create( AssetType.CONTAINER ) );
            assertFalse( Files.exists( container.getFilePath() ),
                         "the container that could not be given its mode was left as a directory" );
        }
        finally
        {
            FileUtils.deleteQuietly( baseDir.toFile() );
        }
    }

    // ---------------------------------------------------------------------------------------------
    // the same assets with their permissions applied, so that nothing above passes on its own
    // ---------------------------------------------------------------------------------------------

    /**
     * An upload whose mode can be applied still succeeds, and does so with the mode the storage promised. Without
     * this, the three tests above would only be showing that the subclass below is a subclass that throws.
     */
    @Test
    public void anUploadWhosePermissionsCouldBeAppliedStillSucceeds()
        throws IOException
    {
        assumePosixFilesystem();
        Path baseDir = Files.createTempDirectory( "archivaPermSucceed" );
        try
        {
            Files.createDirectories( baseDir.resolve( "repo" ) );
            Path stored = baseDir.resolve( "repo/artifact.jar" );
            Files.writeString( stored, STORED_BYTES );

            PermissionFailingAsset asset = asset( baseDir, "repo/artifact.jar", false );
            Path staged = stage( baseDir );

            assertTrue( asset.replaceDataFromFile( staged ), "the staged upload was not promoted" );
            assertEquals( STAGED_BYTES, Files.readString( asset.getFilePath() ),
                          "promoting an upload changed the artifact" );
            assertEquals( FilesystemAsset.DEFAULT_POSIX_FILE_PERMS,
                          PosixFilePermissions.toString( Files.getPosixFilePermissions( asset.getFilePath() ) ),
                          "the promoted artifact does not carry the mode the storage promised" );
        }
        finally
        {
            FileUtils.deleteQuietly( baseDir.toFile() );
        }
    }

    /**
     * And creating an entry whose mode can be applied creates it, so that the exception the tests above expect is
     * the mode and not the entry.
     */
    @Test
    public void anEntryCreatedWhosePermissionsCouldBeAppliedIsCreated()
        throws IOException
    {
        assumePosixFilesystem();
        Path baseDir = Files.createTempDirectory( "archivaPermCreateSucceeds" );
        try
        {
            Files.createDirectories( baseDir.resolve( "repo" ) );

            PermissionFailingAsset asset = asset( baseDir, "repo/artifact.jar", false );
            asset.create( AssetType.FILE );

            assertTrue( Files.isRegularFile( asset.getFilePath() ), "the entry was never created" );
            assertEquals( FilesystemAsset.DEFAULT_POSIX_FILE_PERMS,
                          PosixFilePermissions.toString( Files.getPosixFilePermissions( asset.getFilePath() ) ),
                          "the created entry does not carry the mode the storage promised" );
        }
        finally
        {
            FileUtils.deleteQuietly( baseDir.toFile() );
        }
    }

    // ---------------------------------------------------------------------------------------------
    // the pieces
    // ---------------------------------------------------------------------------------------------

    private static PermissionFailingAsset asset( Path baseDir, String relativePath, boolean permissionsFail )
        throws IOException
    {
        FilesystemStorage storage = new FilesystemStorage( baseDir, new DefaultFileLockManager() );
        return new PermissionFailingAsset( storage, "/" + relativePath, baseDir.resolve( relativePath ), baseDir,
                                           false, true, permissionsFail );
    }

    /** The staged upload, as the repository layer hands one over: a file somewhere else, waiting to be moved in. */
    private static Path stage( Path baseDir )
        throws IOException
    {
        Path staged = baseDir.resolve( "staged.jar" );
        Files.writeString( staged, STAGED_BYTES );
        return staged;
    }

    /**
     * An asset whose mode either can or cannot be applied. The refusal is injected at {@code setPosixPermissions}
     * and nowhere else, so the code that decides which mode an entry gets - the directory and file branches of
     * {@code applyDefaultPermissions} - runs in every test here, and what is missing from it when the test passes is
     * only the reporting of the failure.
     *
     * <p>The exception is the one a filesystem gives for exactly this case: {@code EPERM}, which is what
     * {@code setPosixFilePermissions} answers on an NFS mount with root-squash or a file owned by another user.
     * That state cannot be produced from outside a test, because whatever refuses the chmod refuses the publish that
     * comes first - both act on the same inode - which leaves the entry never created rather than created unreadable.
     */
    private static final class PermissionFailingAsset
        extends FilesystemAsset
    {
        private final boolean permissionsFail;

        PermissionFailingAsset( FilesystemStorage storage, String path, Path assetPath, Path basePath,
                                boolean directory, boolean setPermissionsForNew, boolean permissionsFail )
        {
            super( storage, path, assetPath, basePath, directory, setPermissionsForNew );
            this.permissionsFail = permissionsFail;
        }

        @Override
        protected void setPosixPermissions( Path filePath, Set<PosixFilePermission> perms )
            throws IOException
        {
            if ( permissionsFail )
            {
                throw new AccessDeniedException( "Could not set permissions for " + filePath
                                                     + ": Operation not permitted" );
            }
            super.setPosixPermissions( filePath, perms );
        }
    }

    private static void assumePosixFilesystem()
        throws IOException
    {
        Path root = Paths.get( "." ).toAbsolutePath().getRoot();
        assumeTrue( root != null && Files.getFileStore( root )
                                        .supportsFileAttributeView( PosixFileAttributeView.class ),
                    "requires a filesystem that supports POSIX file permissions" );
    }
}
