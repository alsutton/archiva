package org.apache.archiva.xml;

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
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * What {@link XMLReader} will accept from a document, which is to say what a stranger can make the Archiva service
 * user do.
 *
 * <p>The only production consumer of this parser is {@code MavenMetadataReader}, and a {@code maven-metadata.xml}
 * arrives from two directions a stranger controls: it is downloaded verbatim from whatever remote repository is
 * being proxied, and it is accepted over a WebDAV {@code PUT} from anyone with write rights. The parser runs as the
 * Archiva service user, so an entity that names a file is that user reading a file, and an entity that names a URL
 * is that user making a request nobody asked for.
 *
 * <p>The protections were in this file commented out, and {@code FEATURE_SECURE_PROCESSING} - the one that was live
 * - only bounds entity <em>expansion</em>: it resolves an external entity happily, and so does
 * {@code setExpandEntityReferences(false)}, which merely keeps the result out of the tree after the read happened.
 * So the rule pinned here is that a document type declaration is refused outright, since nothing a metadata document
 * legitimately contains refers to anything outside itself.
 *
 * <p>Refusing a document type is only safe while it is refused <em>first</em>, so the ordinary documents are here to
 * be read: a parser that fails on everything passes the hostile cases for the wrong reason. Both directions are
 * asserted on the same document, differing only by their declaration.
 *
 * @see <a href="https://github.com/alsutton/archiva/issues/70">issue #70</a>
 */
public class XMLReaderExternalEntityTest
{
    private static final String SECRET = "a file the service user can read and a deployer cannot";

    /**
     * A metadata document of the shape {@code MavenMetadataReader} reads, with whatever the test wants in the
     * version element.
     */
    private static final String METADATA =
        "<metadata modelVersion=\"1.1.0\">\n" + "  <groupId>com.example.payroll</groupId>\n"
            + "  <artifactId>payroll-core</artifactId>\n" + "  <version>%s</version>\n"
            + "  <versioning>\n" + "    <lastUpdated>20261107000000</lastUpdated>\n" + "    <versions>\n"
            + "      <version>1.4.1</version>\n" + "      <version>1.4.2</version>\n" + "    </versions>\n"
            + "  </versioning>\n" + "</metadata>\n";

    @Rule
    public final TemporaryFolder temp = new TemporaryFolder();

    /**
     * A file outside the repository, holding something no metadata document has any business containing.
     */
    private Path secret;

    /**
     * Names the documents written below, so that two reads in one test are two files.
     */
    private int documents;

    private Path secret()
        throws IOException
    {
        if ( secret == null )
        {
            secret = Files.write( temp.newFile( "not-a-repository" ).toPath(), SECRET.getBytes( StandardCharsets.UTF_8 ) );
        }
        return secret;
    }

    // ---------------------------------------------------------------------------------------------
    // what a document is refused for containing
    // ---------------------------------------------------------------------------------------------

    /**
     * The external entity: a declaration naming a file, and a reference that asks for it. Both halves are refused,
     * and the file is left where it is.
     */
    @Test
    public void aDocumentThatNamesAFileToReadIsRefused()
        throws Exception
    {
        XMLException refused = assertThrows( XMLException.class, () -> read( externalEntityDocument() ) );

        assertFalse( "the answer is a refusal, not the file: " + refused.getMessage(),
                     refused.getMessage().contains( SECRET ) );
        assertEquals( "the file was left alone", SECRET, contents( secret() ) );
    }

    /**
     * The document type is the difference, and nothing else: the bytes below the declaration are ordinary metadata
     * that parses and answers, so a refusal above cannot be my fixture failing to parse for a reason of its own.
     */
    @Test
    public void theSameDocumentWithoutItsDeclarationIsRead()
        throws Exception
    {
        String document = String.format( METADATA, "1.4.2" );

        assertThrows( XMLException.class, () -> read( declarationNaming( secret() ) + document ) );

        assertEquals( "1.4.2", read( document ).getElementText( "//metadata/version" ) );
    }

    /**
     * An entity defined in the document and expanded a dozen times over needs no file and no URL, and is the case
     * {@code FEATURE_SECURE_PROCESSING} was thought to cover: it bounds how far an expansion may go, which is a
     * different question from whether the document is allowed to declare entities at all.
     */
    @Test
    public void aDocumentThatExpandsItselfIntoNothingIsRefused()
        throws Exception
    {
        String document =
            "<!DOCTYPE metadata [ <!ENTITY a \"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa\">\n"
                + "  <!ENTITY b \"&a;&a;&a;&a;&a;&a;&a;&a;&a;&a;\">\n" + "  <!ENTITY c \"&b;&b;&b;&b;&b;&b;&b;&b;&b;&b;\">\n"
                + "  <!ENTITY d \"&c;&c;&c;&c;&c;&c;&c;&c;&c;&c;\">\n" + "  <!ENTITY e \"&d;&d;&d;&d;&d;&d;&d;&d;&d;&d;\"> ]>\n"
                + String.format( METADATA, "&e;" );

        assertThrows( XMLException.class, () -> read( document ) );
    }

    /**
     * A parameter entity in the internal subset is how the declaration reaches a file without ever appearing as a
     * value, so it is refused on the declaration alone rather than on what the reference asks for.
     */
    @Test
    public void aDocumentWhoseDeclarationReadsWithoutBeingAskedIsRefused()
        throws Exception
    {
        String document = declarationNaming( secret() ) + String.format( METADATA, "1.4.2" );

        XMLException refused = assertThrows( XMLException.class, () -> read( document ) );

        assertFalse( "the answer is a refusal, not the file: " + refused.getMessage(),
                     refused.getMessage().contains( SECRET ) );
    }

    /**
     * The same document naming a URL instead of a file. A reference out of a parsed document is a request the
     * service makes from inside the network, and the address is a host only the service can reach: what comes back
     * is then delivered to whoever sent the document.
     *
     * <p>Whether the parser would have followed this one is a property of the parser, so it is not what the test
     * asks. It asks the port whether anything arrived, and only then asks whether the document was refused: a parse
     * that failed <em>after</em> making the call has still made it, and that is the half a refusal would hide.
     */
    @Test
    public void aDocumentThatNamesAUrlToFetchIsRefusedWithoutMakingTheRequest()
        throws Exception
    {
        boolean refused;

        try (ServerSocket listener = new ServerSocket( 0 ))
        {
            String document =
                "<!DOCTYPE metadata [ <!ENTITY stolen SYSTEM \"http://localhost:" + listener.getLocalPort()
                    + "/internal\"> ]>\n" + String.format( METADATA, "&stolen;" );

            try
            {
                read( document );
                refused = false;
            }
            catch ( XMLException expected )
            {
                refused = true;
            }

            listener.setSoTimeout( 500 );
            try (Socket request = listener.accept())
            {
                fail( "the parse reached out to " + request.getInetAddress() + ":" + listener.getLocalPort()
                    + " - " + ( refused ? "and refused the document afterwards" : "and read the document" ) );
            }
            catch ( SocketTimeoutException nothingArrived )
            {
                // the port heard nothing, which is the answer wanted
            }
        }

        assertTrue( "a document whose declaration names a URL is refused outright", refused );
    }

    // ---------------------------------------------------------------------------------------------
    // what a document is still read for
    // ---------------------------------------------------------------------------------------------

    /**
     * The reason this parser exists, and the one thing hardening must not cost: a metadata document from a real
     * repository, read the way {@code MavenMetadataReader} reads it.
     */
    @Test
    public void anOrdinaryMetadataDocumentIsRead()
        throws Exception
    {
        XMLReader reader = read( String.format( METADATA, "1.4.2" ) );

        assertEquals( "com.example.payroll", reader.getElementText( "//metadata/groupId" ) );
        assertEquals( "payroll-core", reader.getElementText( "//metadata/artifactId" ) );
        assertEquals( "1.4.2", reader.getElementText( "//metadata/version" ) );
        assertEquals( "20261107000000", reader.getElementText( "//metadata/versioning/lastUpdated" ) );

        List<String> versions = reader.getElementListText( "//metadata/versioning/versions/version" );
        assertEquals( Arrays.asList( "1.4.1", "1.4.2" ), versions );
    }

    /**
     * Entities that are part of the document - the ones XML defines, and the latin ones
     * {@link LatinEntityResolutionReader} exists to translate because XML does not define them - are text about an
     * artifact, not a reference to somewhere else, and survive untouched.
     */
    @Test
    public void entitiesThatArePartOfTheDocumentArriveAsText()
        throws Exception
    {
        String document = String.format( METADATA, "1.4.2" ).replace( "<artifactId>payroll-core</artifactId>",
                                                                      "<artifactId>Trygve Laugst&oslash;l &amp; the &infin; "
                                                                          + "archiva &#65;</artifactId>" );

        XMLReader reader = read( document );

        assertEquals( "Trygve Laugstøl & the ∞ archiva A", reader.getElementText( "//metadata/artifactId" ) );
    }

    /**
     * Refusal is not the same as silence: a document refused for one reason must not be reported as a document that
     * happened to be empty, which is what a swallowed parse error would look like to a caller.
     */
    @Test
    public void aRefusalSaysThatTheDocumentWasRefused()
        throws Exception
    {
        XMLException refused = assertThrows( XMLException.class, () -> read( externalEntityDocument() ) );

        assertTrue( "the caller is told the document was not parsed: " + refused.getMessage(),
                    refused.getMessage().startsWith( "Unable to parse file" ) );
    }

    // ---------------------------------------------------------------------------------------------
    // the parser, and the documents it is shown
    // ---------------------------------------------------------------------------------------------

    /**
     * The shape this attack is usually attempted in: a declaration that names a file, and a reference in the body
     * that asks for it as the version of an artifact.
     */
    private String externalEntityDocument()
        throws IOException
    {
        return "<!DOCTYPE metadata [ <!ENTITY stolen SYSTEM \"" + uri( secret() ) + "\"> ]>\n"
            + String.format( METADATA, "&stolen;" );
    }

    /**
     * A declaration whose entities are defined but never referenced: it asks for the file by itself, the moment the
     * declaration is read. What the document goes on to say is then irrelevant, which is the point.
     */
    private static String declarationNaming( Path target )
    {
        return "<!DOCTYPE metadata [ <!ENTITY % peek SYSTEM \"" + uri( target ) + "\">%peek; ]>\n";
    }

    /**
     * @throws XMLException the way the callers see it, which is what makes this a test of them and not of the parser
     */
    private XMLReader read( String document )
        throws Exception
    {
        return new XMLReader( "metadata", file( document ) );
    }

    /**
     * Written one at a time and never overwritten: a test that reads a document and then reads it again without its
     * declaration is two documents, and the second has to be the one it claims to be rather than the first under
     * the same name.
     */
    private Path file( String document )
        throws IOException
    {
        return Files.write( temp.newFile( "maven-metadata-" + ++documents + ".xml" ).toPath(),
                            document.getBytes( StandardCharsets.UTF_8 ) );
    }

    private static String uri( Path file )
    {
        return file.toUri().toString();
    }

    private static String contents( Path file )
        throws IOException
    {
        return new String( Files.readAllBytes( file ), StandardCharsets.UTF_8 );
    }
}
