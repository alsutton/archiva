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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.charset.Charset;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.CopyOption;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Comparator;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Utility class for file manipulation
 *
 * @author Martin Stockhammer <martin_s@apache.org>
 */
public class FileUtils {
    private static final Logger log = LoggerFactory.getLogger(FileUtils.class);

    /**
     * Infix of the temporary files created by {@link #writeAtomically(Path, ContentWriter)}.
     */
    private static final String TMP_FILE_INFIX = ".tmp-";

    /**
     * Counter for temporary file names, seeded with the process start time so that two processes
     * sharing a repository directory do not immediately pick the same names.
     */
    private static final AtomicInteger TMP_FILE_COUNTER = new AtomicInteger((int) (System.nanoTime() & 0xFFFF));

    /**
     * Deletes the directory recursively and quietly.
     *
     * @param dir
     */
    public static void deleteQuietly(Path dir) {
        try(Stream<Path> stream = Files.walk(dir)) {
            stream
                .sorted( Comparator.reverseOrder() )
                .forEach(file -> {
                        try {
                            Files.delete(file);
                        } catch (IOException e) {
                            // Ignore this
                            if (log.isDebugEnabled()) {
                                log.debug( "Exception during file delete: {}", e.getMessage( ), e );
                            }
                        }

                    });
        } catch (IOException e) {
            // Ignore this
        }


    }

    public static IOStatus deleteDirectoryWithStatus(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            IOStatus status = new IOStatus( );
            status.addError( dir, new FileNotFoundException( "Directory not found " + dir ) );
            return status;
        }
        if (!Files.isDirectory(dir)) {
            IOStatus status = new IOStatus( );
            status.addError(dir, new IOException("Given path is not a directory " + dir));
        }
        try( Stream<Path> stream = Files.walk(dir)) {
                return stream
                    .sorted( Comparator.reverseOrder() )
                .map(file ->
                {
                    try {
                        Files.delete(file);
                        return new FileStatus( file, StatusResult.DELETED );
                    } catch (UncheckedIOException e) {
                        log.warn("File could not be deleted {}", file);
                        return new FileStatus( file, e.getCause( ) );
                    }
                    catch ( IOException e )
                    {
                        return new FileStatus( file, e );
                    }
                }).collect( IOStatus::new, IOStatus::accumulate, IOStatus::combine );
        } catch (UncheckedIOException e) {
            throw new IOException("File deletion failed ", e);
        }
    }

    public static void deleteDirectory(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            return;
        }
        if (!Files.isDirectory(dir)) {
            throw new IOException("Given path is not a directory " + dir);
        }
        boolean result = true;
        try(Stream<Path> stream = Files.walk(dir)) {
            result = stream
                .sorted( Comparator.reverseOrder() )
                .map(file ->
                    {
                        try {
                            Files.delete(file);
                            return Optional.of(Boolean.TRUE);
                        } catch (UncheckedIOException | IOException e) {
                            log.warn("File could not be deleted {}: {}", file, e.getMessage());
                            return Optional.empty();
                        }

                    }).allMatch(Optional::isPresent);
        } catch (UncheckedIOException e) {
            throw new IOException("File deletion failed ", e);
        }
        if (!result) {
            throw new IOException("Error during recursive delete of " + dir.toAbsolutePath());
        }
    }

    public static String readFileToString(Path file, Charset encoding) {
        try {
            return new String(Files.readAllBytes(file), encoding);
        } catch (IOException e) {
            log.error("Could not read from file {}", file);
            return "";
        }
    }

    /**
     * Writes {@code value} to {@code file}, atomically replacing the target.
     * See {@link #writeAtomically(Path, ContentWriter)} for the guarantees.
     *
     * @throws IOException if the content could not be written in full. Write failures used to be
     *         logged and swallowed, which let callers proceed as if the file were on disk.
     */
    public static void writeStringToFile(Path file, Charset encoding, String value) throws IOException {
        writeAtomically(file, out -> out.write(value.getBytes(encoding)));
    }

    /**
     * Writes the content of a file to the stream handed to it. Used by
     * {@link #writeAtomically(Path, ContentWriter)}; the stream is closed by the caller.
     */
    @FunctionalInterface
    public interface ContentWriter {
        void write(OutputStream out) throws IOException;
    }

    /**
     * Writes {@code file} by filling a temporary file in the same directory, flushing it to the
     * store and renaming it over the target.
     *
     * <p>Two properties are the reason this exists:</p>
     * <ul>
     *     <li><b>no half written files.</b> A concurrent or subsequent reader sees either the previous
     *     content or the complete new content, never an empty or truncated one. For a checksum file a
     *     truncated digest that still parses is worse than a missing one, and a reader that is not
     *     looking at a digest can accept a truncated artifact as complete.</li>
     *     <li><b>visible failures.</b> Every step propagates {@link IOException}: the target is only
     *     replaced once the whole content has reached the store.</li>
     * </ul>
     *
     * <p>Note that the target is replaced rather than written through, so if {@code file} is a
     * symbolic link it is replaced by a regular file.</p>
     *
     * @param file    the file to write
     * @param content receives the stream of the temporary file and writes the whole content to it
     * @throws IOException if the content could not be written completely, in which case {@code file}
     *                     still holds its previous content (or does not exist)
     */
    public static void writeAtomically(Path file, ContentWriter content) throws IOException {
        Path tempFile = newTempFile(file);
        try {
            try (FileChannel channel = FileChannel.open(tempFile, StandardOpenOption.WRITE,
                                                        StandardOpenOption.TRUNCATE_EXISTING);
                 OutputStream out = Channels.newOutputStream(channel)) {
                content.write(out);
                out.flush();
                // make sure the content is at rest before the rename publishes it
                channel.force(true);
            }
            keepPermissions(file, tempFile);
            try {
                Files.move(tempFile, file, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException | FileAlreadyExistsException e) {
                // filesystems that cannot replace an existing file atomically (among them several
                // network mounts). Falling back to a plain replace is not worse than what the caller
                // asked for when it wrote the target directly.
                try {
                    Files.move(tempFile, file, StandardCopyOption.REPLACE_EXISTING);
                } catch (IOException fallbackFailure) {
                    fallbackFailure.addSuppressed(e);
                    throw fallbackFailure;
                }
            }
        } finally {
            // only reached when the move did not happen; a move has already removed the source
            try {
                Files.deleteIfExists(tempFile);
            } catch (IOException e) {
                log.debug("Could not remove temporary file {}: {}", tempFile, e.getMessage());
            }
        }
    }

    /**
     * Creates an empty file next to {@code file} with a name that no other writer of the same target
     * picks, so that two concurrent writes cannot publish each other's content.
     */
    private static Path newTempFile(Path file) throws IOException {
        for (int attempt = 0; attempt < 16; attempt++) {
            Path candidate = file.resolveSibling(file.getFileName() + TMP_FILE_INFIX + TMP_FILE_COUNTER.getAndIncrement());
            try {
                // deliberately Files.createFile and not Files.createTempFile: the latter creates the
                // file readable by its owner only, which would silently change the mode of the file
                // being replaced.
                Files.createFile(candidate);
                return candidate;
            } catch (FileAlreadyExistsException e) {
                // name of an interrupted earlier run, take the next one
            }
        }
        throw new IOException("Could not create a temporary file next to " + file);
    }

    /**
     * Copies the mode of {@code file} to {@code tempFile}, so that replacing the former does not
     * change the permissions of the stored content.
     */
    private static void keepPermissions(Path file, Path tempFile) {
        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        try {
            Files.setPosixFilePermissions(tempFile, Files.getPosixFilePermissions(file));
        } catch (UnsupportedOperationException | IOException e) {
            // not a posix file store, or the mode is unreadable: the replacement then keeps the mode a
            // newly created file gets, which is what the replaced file had when it was created
            log.debug("Permissions of {} could not be preserved on {}: {}", file, tempFile, e.getMessage());
        }
    }

    /**
     * Return the base directory
     *
     * @return
     */
    public static String getBasedir() {
        String basedir = System.getProperty("basedir");
        if (basedir == null) {
            basedir = Paths.get("").toAbsolutePath().toString();
        }

        return basedir;
    }

    /**
     * This checks, if the given child is a absolute path. If this is the case
     * the relative path is used.
     *
     * @param parent The parent directory
     * @param child  The child
     * @return The path parent/child
     */
    public Path resolveNonAbsolute(Path parent, String child) {
        Path childPath = Paths.get(child);
        if (childPath.isAbsolute()) {
            return parent.resolve(childPath.getNameCount() > 0 ? childPath.subpath(0, childPath.getNameCount()) : Paths.get(""));
        } else {
            return parent.resolve(child);
        }
    }

    public static void unzipFileEntry(ZipFile file, ZipEntry entry, Path destinationPath) throws IOException {
        InputStream is = file.getInputStream(entry);
        BufferedInputStream bis = new BufferedInputStream(is);
        Path uncompressedFilePath = destinationPath.resolve(entry.getName());
        Path parentPath = uncompressedFilePath.getParent();
        if (!Files.exists(parentPath)) {
            Files.createDirectories(parentPath);
        }
        Files.createFile(uncompressedFilePath);
        OutputStream fileOutput = Files.newOutputStream(uncompressedFilePath);
        while (bis.available() > 0) {
            fileOutput.write(bis.read());
        }
        fileOutput.close();
    }


    /**
     * Unzips a file into a destination directory. It does not update the modification time according the
     * the date in the zip file. All subdirectories will be created if the zip file contains a directory structure.
     *
     * @param zipFile the path to the zip file
     * @param destinationPath the destination path where the files should be extracted.
     * @throws IOException if an error occurs during extract.
     */
    public static void unzip(Path zipFile, Path destinationPath) throws IOException {
        try (ZipFile file = new ZipFile(zipFile.toFile())) {
            file.stream().forEach(e -> {
                try {
                    if (e.isDirectory()) {
                        Files.createDirectories(destinationPath.resolve(e.getName()));
                    } else {
                        unzipFileEntry(file, e, destinationPath);
                    }
                } catch (IOException ex) {
                    log.error("Error occured during unzip of zipFile={}, entry={}. Message: {}", zipFile, e.getName(), ex.getMessage());
                }
            });
        }
    }


    /**
     * Copies the content of {@code srcDir} into {@code destinationDir}.
     *
     * <p>Every entry that could be copied is copied, and the first failure is thrown once the walk is over: the
     * caller cannot tell a tree that was copied from one that was half copied by looking at the destination, and
     * the only production caller deletes the source on the strength of this method returning
     * ({@code MavenIndexManager} moves an index with {@code copyContent} followed by {@code deleteDirectory}), so a
     * quiet failure destroys the copy that still worked. Reporting it costs the caller the delete, which is the
     * whole point.
     *
     * @throws IOException if any entry could not be copied; later failures are attached to the first one
     */
    public static void copyContent(Path srcDir, Path destinationDir, boolean overwrite) throws IOException
    {
        CopyOption[] copyOption = overwrite ? new CopyOption[]{StandardCopyOption.REPLACE_EXISTING} : new CopyOption[]{};
        IOException firstFailure = null;
        int failed = 0;
        // The walk is a stream of open directory handles: it has to be closed, or a copy over a network mount
        // leaks one handle per directory it touched.
        try (Stream<Path> entries = Files.walk(srcDir))
        {
            for (Path a : (Iterable<Path>) entries::iterator)
            {
                Path b = destinationDir.resolve(srcDir.relativize( a ));
                try {
                    Files.copy(a, b, copyOption);
                } catch (IOException e) {
                    log.error( "Could not copy file {} to {}: {}", a, b, e.getMessage( ), e );
                    failed++;
                    if (firstFailure == null)
                    {
                        firstFailure = e;
                    }
                    else
                    {
                        firstFailure.addSuppressed(e);
                    }
                }
            }
        }
        if (firstFailure != null)
        {
            throw new IOException( "Copy of " + srcDir + " to " + destinationDir + " failed for " + failed
                                       + " of its entries, first: " + firstFailure.getMessage(), firstFailure );
        }
    }

    public static void copyContent(Path srcDir, Path destinationDir) throws IOException {
        copyContent( srcDir, destinationDir, true );
    }
}
