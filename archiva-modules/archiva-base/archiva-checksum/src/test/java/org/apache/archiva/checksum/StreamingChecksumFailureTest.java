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
 *   http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/**
 * What {@link StreamingChecksum#updateChecksums} does when a digest cannot be produced.
 *
 * <p>This is how a WebDAV PUT publishes the {@code .md5} and {@code .sha1} alongside an uploaded artifact:
 * {@code ArchivaDavResourceFactory.createChecksumFiles} opens one output stream per configured algorithm and hands
 * them over with the artifact's own content stream, and whatever this method returns quietly is the digest the
 * repository ends up serving. The whole body used to end in
 *
 * <pre>
 * } catch ( IOException e ) {
 *
 * }
 * </pre>
 *
 * so a write that never happened - a full disk, an emptied directory, an output stream that was never opened for the
 * algorithm it stands for - read to the caller exactly as a stored digest did. The caller then answered the upload
 * with 201 over digest files it had already truncated to zero bytes by opening them for writing. A Maven client
 * fetching the artifact fails on {@code ChecksumValidationException}, and Archiva's own proxy and
 * {@code ArtifactMissingChecksumsConsumer} reject what the same instance just accepted.
 *
 * <p>The digests and the streams are correlated by index, which is why a list that does not match the algorithm list
 * cannot be coped with either: it decides which digest lands in which file. The control below pins that pairing, and
 * the flush test pins that the bytes are actually handed to the stream, since the caller closes it and an unflushed
 * channel is how a digest file ends up empty on disk.
 *
 * @see <a href="https://github.com/alsutton/archiva/issues/76">issue #76</a>
 */
public class StreamingChecksumFailureTest
{
    /** The artifact being published. */
    private static final byte[] ARTIFACT = "the bytes of the artifact being deployed".getBytes( StandardCharsets.UTF_8 );

    private static final List<ChecksumAlgorithm> ALGORITHMS =
        Arrays.asList( ChecksumAlgorithm.MD5, ChecksumAlgorithm.SHA1 );

    // ---------------------------------------------------------------------------------------------
    // the failures that used to disappear
    // ---------------------------------------------------------------------------------------------

    /**
     * A digest that cannot be written has to reach the caller. Nothing else in the stack can find out: the file was
     * opened for writing before the call, which emptied whatever digest was there before, so a quiet return publishes
     * an upload whose digests are zero bytes.
     */
    @Test
    public void aDigestThatCouldNotBeStoredIsReportedToTheCaller()
    {
        OutputStream sha1 = refusing( "No space left on device: artifact.jar.sha1" );

        assertThrows( "a digest that never reached the disk was reported as stored", IOException.class,
                      () -> StreamingChecksum.updateChecksums( source(), ALGORITHMS,
                                                               Arrays.asList( writing(), sha1 ) ) );
    }

    /**
     * A source that fails half way through means the digest computed so far is about a prefix of the artifact. It is
     * not a digest of anything, and writing it out would be worse than reporting the failure.
     */
    @Test
    public void anArtifactThatCouldNotBeReadIsReportedToTheCaller()
    {
        InputStream broken = failingAfterTenBytes();

        IOException thrown =
            assertThrows( "a content stream that gave up half way was reported as a digest", IOException.class,
                          () -> StreamingChecksum.updateChecksums( broken, ALGORITHMS,
                                                                   Arrays.asList( writing(), writing() ) ) );

        assertTrue( "not the reason the reading stopped: " + thrown.getMessage(),
                    thrown.getMessage() != null && thrown.getMessage().contains( "read" ) );
    }

    /**
     * One stream per algorithm, in the same order, is what the pairing below depends on. A caller that dropped the
     * stream it could not open used to leave the list shorter than the algorithm list, which either put one
     * algorithm's digest into the next one's file or stopped on an unchecked index-out-of-bounds inside the swallowed
     * block - neither of which the caller, catching only {@code IOException}, was in a position to notice.
     */
    @Test
    public void aDigestWithNoStreamOfItsOwnIsReportedToTheCaller()
    {
        List<ChecksumAlgorithm> three =
            Arrays.asList( ChecksumAlgorithm.MD5, ChecksumAlgorithm.SHA1, ChecksumAlgorithm.SHA512 );
        List<OutputStream> two = Arrays.asList( writing(), writing() );

        assertThrows( "a digest with nowhere to go was not reported", IOException.class,
                      () -> StreamingChecksum.updateChecksums( source(), three, two ) );
    }

    /**
     * The bytes of a digest have to be handed to the stream by the time this method returns: the caller opened the
     * stream and the caller closes it, and an unflushed channel is the other way a checksum file is published at zero
     * length.
     */
    @Test
    public void aStoredDigestHasBeenHandedOverToBeWritten()
        throws IOException
    {
        Flushing md5 = new Flushing();
        Flushing sha1 = new Flushing();

        StreamingChecksum.updateChecksums( source(), ALGORITHMS, Arrays.asList( md5, sha1 ) );

        assertTrue( "the md5 digest was never flushed, so closing the stream can lose it", md5.wasFlushed );
        assertTrue( "the sha1 digest was never flushed, so closing the stream can lose it", sha1.wasFlushed );
    }

    // ---------------------------------------------------------------------------------------------
    // the case that has always worked, so that the tests above mean something
    // ---------------------------------------------------------------------------------------------

    /**
     * Each algorithm's digest goes to the stream at its own index, and nothing else is written. The digests come from
     * {@link MessageDigest} directly rather than from this module's own {@link Checksum}.
     */
    @Test
    public void eachDigestLandsInTheStreamOfItsOwnAlgorithm()
        throws IOException, NoSuchAlgorithmException
    {
        ByteArrayOutputStream md5 = writing();
        ByteArrayOutputStream sha1 = writing();

        StreamingChecksum.updateChecksums( source(), ALGORITHMS, Arrays.asList( md5, sha1 ) );

        assertEquals( "the md5 file does not hold the md5 of the artifact", hex( "MD5" ),
                      md5.toString( StandardCharsets.US_ASCII.name() ) );
        assertEquals( "the sha1 file does not hold the sha1 of the artifact", hex( "SHA-1" ),
                      sha1.toString( StandardCharsets.US_ASCII.name() ) );
    }

    // ---------------------------------------------------------------------------------------------
    // the pieces
    // ---------------------------------------------------------------------------------------------

    /** The artifact, as the repository hands it over. */
    private static InputStream source()
    {
        return new InputStream()
        {
            private int at;

            @Override
            public int read()
            {
                return at < ARTIFACT.length ? ARTIFACT[ at++ ] & 0xff : -1;
            }

            @Override
            public int read( byte[] b, int off, int len )
            {
                if ( at >= ARTIFACT.length )
                {
                    return -1;
                }
                int n = Math.min( len, ARTIFACT.length - at );
                System.arraycopy( ARTIFACT, at, b, off, n );
                at += n;
                return n;
            }
        };
    }

    /** A content stream that stops in the middle of the artifact. */
    private static InputStream failingAfterTenBytes()
    {
        return new InputStream()
        {
            private int remaining = 10;

            @Override
            public int read()
                throws IOException
            {
                if ( remaining <= 0 )
                {
                    throw new IOException( "the disk read of the artifact failed" );
                }
                remaining--;
                return 'x';
            }

            @Override
            public int read( byte[] b, int off, int len )
                throws IOException
            {
                if ( remaining <= 0 )
                {
                    throw new IOException( "the disk read of the artifact failed" );
                }
                int n = Math.min( len, remaining );
                Arrays.fill( b, off, off + n, (byte) 'x' );
                remaining -= n;
                return n;
            }
        };
    }

    /** A digest file that refuses to take the digest. */
    private static OutputStream refusing( String reason )
    {
        return new OutputStream()
        {
            @Override
            public void write( int b )
                throws IOException
            {
                throw new IOException( reason );
            }

            @Override
            public void write( byte[] b, int off, int len )
                throws IOException
            {
                throw new IOException( reason );
            }
        };
    }

    private static ByteArrayOutputStream writing()
    {
        return new ByteArrayOutputStream();
    }

    /** A digest file that records whether it was ever told to get its content out. */
    private static final class Flushing
        extends FilterOutputStream
    {
        private boolean wasFlushed;

        /** The digest as it arrived, kept because {@link #out} is the sink the parent would push to. */
        private final ByteArrayOutputStream content = new ByteArrayOutputStream();

        Flushing()
        {
            super( new ByteArrayOutputStream() );
        }

        @Override
        public void write( int b )
        {
            content.write( b );
        }

        @Override
        public void write( byte[] b, int off, int len )
        {
            content.write( b, off, len );
        }

        @Override
        public void flush()
        {
            wasFlushed = true;
        }
    }

    private static String hex( String algorithm )
        throws NoSuchAlgorithmException
    {
        StringBuilder text = new StringBuilder();
        for ( byte b : MessageDigest.getInstance( algorithm ).digest( ARTIFACT ) )
        {
            text.append( String.format( "%02x", b ) );
        }
        return text.toString();
    }
}
