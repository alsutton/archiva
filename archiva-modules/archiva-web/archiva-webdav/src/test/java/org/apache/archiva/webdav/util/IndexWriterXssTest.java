package org.apache.archiva.webdav.util;

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

import org.apache.archiva.repository.storage.StorageAsset;
import org.apache.jackrabbit.webdav.io.OutputContext;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * {@link IndexWriter} writes a page out of strings it did not write: the collection path comes from the request, and
 * the entry names come from the directory being listed, which is a directory a deployer with write rights created.
 * The response is {@code text/html}, so a value that reaches the page unchanged is markup, and it runs for whoever
 * browses the listing - a deployer against whoever reads it, and a request against itself.
 *
 * <p>Both the ways a value is used are pinned, because they need different treatment and getting one right is not
 * enough: as <em>text</em> a value has to be escaped, and as a <em>link target</em> it has to be encoded, since an
 * escaped {@code javascript:} is still a scheme and still runs on a click.
 *
 * <p>The ordinary names are here too, because a listing that stopped working, or that showed every file in the
 * instance as a sequence of per cent signs, would be safe and useless.
 */
public class IndexWriterXssTest
{
    /**
     * Names a deployer can put in a repository, each of which used to be part of the page rather than text on it.
     */
    private static final List<String> FILES = Arrays.asList( "x<img src=x onerror=alert(1)>.jar",
                                                             "a\" onload=\"alert(1)",
                                                             "javascript:alert(1).html",
                                                             "data:text/html,<script>alert(1)</script>",
                                                             "<script>alert(1)</script>",
                                                             "100%-of-it.jar",
                                                             "a+plus.jar",
                                                             "commons-lang3-3.12.0.jar" );

    /**
     * The same, as directories, which are written with a trailing slash on the target and so are a second chance for
     * a name to end up somewhere it was not meant to.
     */
    private static final List<String> DIRECTORIES =
        Arrays.asList( "a directory<img>/", "a\" onload=\"alert(1)/" );

    /**
     * The tags {@link IndexWriter} writes, and no others. A name that turns up in the page as a tag of a different
     * name got in as markup, which is what a browser was meant to run.
     */
    private static final Set<String> WRITERS_OWN_TAGS = new LinkedHashSet<>(
        Arrays.asList( "html", "head", "title", "style", "link", "body", "h3", "ul", "li", "a", "i", "small", "table",
                       "tr", "th", "td" ) );

    private static final Pattern TAG_NAME = Pattern.compile( "</?([A-Za-z][A-Za-z0-9]*)" );

    private static final Pattern HREF = Pattern.compile( "href=\"([^\"]*)\"" );

    // ---------------------------------------------------------------------------------------------
    // a name as the page reads it: text
    // ---------------------------------------------------------------------------------------------

    /**
     * Every name in the listing arrives as text, whatever it looks like: the tags in the response are the tags this
     * class writes, and nothing a file or a directory was called contributed one.
     */
    @Test
    public void entryNamesAreText()
        throws Exception
    {
        String page = listing( "releases/com/example", allNames() );

        assertEquals( "a name got into the page as markup: " + page, WRITERS_OWN_TAGS, tagNamesIn( page ) );
    }

    /**
     * Escaping is not dropping: a name a deployer gave a file is still the name the listing shows, because that is
     * the name of the file, and a listing nobody can read is not a fix.
     */
    @Test
    public void entryNamesAreStillShownInFull()
        throws Exception
    {
        String page = listing( "releases", FILES );

        assertTrue( "the name is still readable: " + page,
                    page.contains( "x&lt;img src=x onerror=alert(1)&gt;.jar" ) );
        assertTrue( "with its markup visible as text: " + page,
                    page.contains( "&lt;script&gt;alert(1)&lt;/script&gt;" ) );
    }

    /**
     * The collection path is the request, reflected twice - in the title and in the heading - at a URL a phisher only
     * has to send somebody.
     */
    @Test
    public void theCollectionPathIsText()
        throws Exception
    {
        String page = listing( "releases/com/example<script>alert(1)</script>", Arrays.asList( "pom.xml" ) );

        assertEquals( "the path got into the page as markup: " + page, WRITERS_OWN_TAGS, tagNamesIn( page ) );
        assertTrue( "and the path is still the path: " + page,
                    page.contains( "Collection: /releases/com/example&lt;script&gt;alert(1)&lt;/script&gt;" ) );
    }

    // ---------------------------------------------------------------------------------------------
    // a name as the page links to it: a target, not text
    // ---------------------------------------------------------------------------------------------

    /**
     * An attribute is a worse place to be than text: a name with a quote in it left the value and became an attribute
     * of the writer's own link, which is an event handler on a page the victim chose to trust.
     */
    @Test
    public void entryNamesCannotLeaveTheirAttribute()
        throws Exception
    {
        String page = listing( "releases", allNames() );

        Set<String> hrefs = hrefsIn( page );

        // the entries, and the two links every page of this writer's output carries whatever it is listing: the
        // parent, and the icon
        assertEquals( "one link per entry, plus the page's own parent and icon: " + page, allNames().size() + 2,
                      hrefs.size() );

        for ( String href : hrefs )
        {
            // a path segment and the separators between them, and nothing that could read as anything else: no
            // quote to end the attribute, no angle to start a tag, no colon to start a scheme
            assertTrue( "a link is a path: " + href, href.matches( "[A-Za-z0-9%._~/-]*" ) );
            assertFalse( "a link carries no markup: " + href, href.contains( "<" ) || href.contains( "&" ) );
        }
    }

    /**
     * {@code javascript:} and {@code data:} survive HTML escaping, because they are not markup: they are the value of
     * an attribute, read as a reference. A name that reads as a scheme has to be made into a path before it can be
     * linked to.
     */
    @Test
    public void aNameThatLooksLikeASchemeIsStillAPath()
        throws Exception
    {
        String page = listing( "releases", Arrays.asList( "javascript:alert(1).html", "data:text/html,x.html" ) );

        String links = hrefsIn( page ).toString();

        assertFalse( "no link executes: " + links, links.toLowerCase().contains( "javascript:" ) );
        assertFalse( "no link is an inline document: " + links, links.toLowerCase().contains( "data:" ) );
        assertTrue( "both are still linked to: " + links, links.contains( "javascript%3Aalert%281%29.html" ) );
    }

    /**
     * Encoding is not renaming: every link leads back to the entry it was written for, including the names that were
     * already broken links before this - a space, a quote, a per cent sign, a plus, a colon.
     */
    @Test
    public void everyLinkLeadsBackToItsEntry()
        throws Exception
    {
        String page = listing( "releases", allNames() );

        Set<String> hrefs = hrefsIn( page );

        for ( String name : allNames() )
        {
            // the name is what the entry is called, the target is that with a slash on the end if it is a directory
            String name_ = name.endsWith( "/" ) ? name.substring( 0, name.length() - 1 ) : name;
            String target = URLEncoder.encode( name_, StandardCharsets.UTF_8 )
                                      .replace( "+", "%20" )
                                      .replace( ":", "%3A" );

            assertTrue( "no link for " + name + " in " + page,
                        hrefs.contains( name.endsWith( "/" ) ? target + "/" : target ) );
        }
    }

    // ---------------------------------------------------------------------------------------------
    // what an ordinary listing looks like, so that escaping cannot be mistaken for silence
    // ---------------------------------------------------------------------------------------------

    /**
     * A name with nothing in it is written unchanged, in the text and in the link: escaping is for the names that
     * need it, and doing it twice would show on every page of every repository in the instance.
     */
    @Test
    public void anOrdinaryEntryIsWrittenAsItWasCalled()
        throws Exception
    {
        String page = listing( "releases/org/apache/commons", Arrays.asList( "commons-lang3-3.12.0.jar" ) );

        assertTrue( "as text: " + page, page.contains( ">commons-lang3-3.12.0.jar</a>" ) );
        assertTrue( "as a link: " + page, page.contains( "href=\"commons-lang3-3.12.0.jar\"" ) );
        assertTrue( "and the page is about the directory it is listing: " + page,
                    page.contains( "<h3>Collection: /releases/org/apache/commons</h3>" ) );
    }

    /**
     * The parent link is the one name the writer does not take from a listing, and it is a path from a request like
     * any other.
     */
    @Test
    public void theParentLinkIsTheParentOfTheCollection()
        throws Exception
    {
        String page = listing( "releases/com/example", Arrays.asList( "pom.xml" ) );

        assertTrue( "up, with the parent named as text: " + page,
                    page.contains( "<a class=\"folder\" href=\"../\">releases/com</a>" ) );
    }

    // ---------------------------------------------------------------------------------------------
    // rendering, and the WebDAV it is rendered into
    // ---------------------------------------------------------------------------------------------

    private static List<String> allNames()
    {
        List<String> names = new ArrayList<>( FILES );
        names.addAll( DIRECTORIES );
        return names;
    }

    /**
     * The page a GET on {@code logicalResource} would produce, listing {@code names} as its entries.
     */
    private String listing( String logicalResource, List<String> names )
        throws Exception
    {
        ByteArrayOutputStream body = new ByteArrayOutputStream();

        List<StorageAsset> entries = new ArrayList<>();
        for ( String name : names )
        {
            entries.add( entry( name ) );
        }

        new IndexWriter( asset( "collection", true, entries ), logicalResource ).write( recording( body ) );

        return new String( body.toByteArray(), StandardCharsets.UTF_8 );
    }

    private Set<String> tagNamesIn( String page )
    {
        Set<String> tags = new LinkedHashSet<>();
        Matcher matcher = TAG_NAME.matcher( page );
        while ( matcher.find() )
        {
            tags.add( matcher.group( 1 ).toLowerCase() );
        }
        return tags;
    }

    private Set<String> hrefsIn( String page )
    {
        Set<String> hrefs = new LinkedHashSet<>();
        Matcher matcher = HREF.matcher( page );
        while ( matcher.find() )
        {
            hrefs.add( matcher.group( 1 ) );
        }
        return hrefs;
    }

    /**
     * An entry called {@code name}, which is a directory if that name ends in a slash - the slash being how this
     * test says so, and not part of the name.
     */
    private static StorageAsset entry( String name )
    {
        boolean directory = name.endsWith( "/" );
        return asset( directory ? name.substring( 0, name.length() - 1 ) : name, directory, null );
    }

    /**
     * A {@link StorageAsset} that answers the handful of questions a listing asks, and fails the test on any other,
     * so that no test here can pass because the writer stopped asking anything.
     */
    private static StorageAsset asset( String name, boolean container, List<StorageAsset> children )
    {
        return (StorageAsset) Proxy.newProxyInstance( IndexWriterXssTest.class.getClassLoader(),
                                                      new Class<?>[] { StorageAsset.class },
                                                      new Listing( name, container, children ) );
    }

    private static final class Listing implements InvocationHandler
    {
        private final String name;

        private final boolean container;

        private final List<StorageAsset> children;

        private Listing( String name, boolean container, List<StorageAsset> children )
        {
            this.name = name;
            this.container = container;
            this.children = children;
        }

        @Override
        public Object invoke( Object proxy, Method method, Object[] args )
        {
            switch ( method.getName() )
            {
                case "list":
                    return children == null ? new ArrayList<StorageAsset>() : children;
                case "getName":
                    return name;
                case "isContainer":
                    return container;
                case "isLeaf":
                    return !container;
                case "getModificationTime":
                    return Instant.ofEpochMilli( 1730000000000L );
                case "getSize":
                    return (long) name.length();
                case "exists":
                    return Boolean.TRUE;
                case "toString":
                    return "StorageAsset (listing) " + name;
                case "hashCode":
                    return System.identityHashCode( proxy );
                case "equals":
                    return proxy == args[0];
                default:
                    throw new AssertionError( "unexpected call: StorageAsset." + method.getName()
                        + " - stub it, or say why a listing should be asking" );
            }
        }
    }

    /**
     * The response being written. Four of its methods are about the page; the rest are the status and headers a
     * container would set around it, which are not under test and are answered by doing nothing.
     */
    private static OutputContext recording( ByteArrayOutputStream body )
    {
        return (OutputContext) Proxy.newProxyInstance( IndexWriterXssTest.class.getClassLoader(),
                                                       new Class<?>[] { OutputContext.class },
                                                       (InvocationHandler) ( proxy, method, args ) ->
                                                       {
                                                           switch ( method.getName() )
                                                           {
                                                               case "hasStream":
                                                                   return Boolean.TRUE;
                                                               case "getOutputStream":
                                                                   return body;
                                                               case "toString":
                                                                   return "OutputContext (recording)";
                                                               case "hashCode":
                                                                   return System.identityHashCode( proxy );
                                                               case "equals":
                                                                   return proxy == args[0];
                                                               default:
                                                                   return null;
                                                           }
                                                       } );
    }
}
