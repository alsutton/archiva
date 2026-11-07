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
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

import org.apache.archiva.repository.storage.StorageAsset;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.text.StringEscapeUtils;
import org.apache.jackrabbit.webdav.io.OutputContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.PrintWriter;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;

/**
 * Writes the HTML listing of a WebDAV collection.
 *
 * <p>Everything interpolated into it is text the writer was handed, not text it chose: the collection path comes
 * from the request, and the entry names come from directories and files that a deployer with write rights created.
 * The response is {@code text/html}, so a value that reaches the page unchanged is markup, and it runs for whoever
 * browses the listing - which is why each one is escaped here, at the point it becomes output, rather than by a
 * caller that would have to know this page exists.
 */
public class IndexWriter
{

    private static final Logger log = LoggerFactory.getLogger( IndexWriter.class );

    private final String logicalResource;

    private final List<StorageAsset> repositoryAssets;


    private final boolean isVirtual;

    public IndexWriter( StorageAsset reference, String logicalResource )
    {
        this.repositoryAssets = new ArrayList<>(  );
        this.repositoryAssets.add(reference);
        this.logicalResource = logicalResource;
        this.isVirtual = false;
    }

    public IndexWriter( List<StorageAsset> localResources, String logicalResource )
    {
        this.logicalResource = logicalResource;
        this.repositoryAssets = localResources;
        this.isVirtual = true;
    }

    public void write( OutputContext outputContext )
    {
        outputContext.setModificationTime( new Date().getTime() );
        outputContext.setContentType( "text/html" );
        outputContext.setETag( "" ); // skygo ETag MRM-1127 seems to be fixed
        if ( outputContext.hasStream() )
        {
            PrintWriter writer = new PrintWriter( outputContext.getOutputStream() );
            writeDocumentStart( writer );
            try
            {
                writeHyperlinks( writer );
            }
            catch ( IOException e )
            {
                log.error("Could not write hyperlinks {}", e.getMessage(), e);
            }
            writeDocumentEnd( writer );
            writer.flush();
            writer.close();
        }
    }

    private void writeDocumentStart( PrintWriter writer )
    {
        writer.println("<!DOCTYPE html>");
        writer.println( "<html>" );
        writer.println( "<head>" );
        writer.println( "<title>" + text( "Collection: /" + logicalResource ) + "</title>" );
        writer.println( "<style type=\"text/css\">" );
        writer.println( "ul{list-style:none;}" ); 
        
        // the one value the page is given that the request does not influence: the path is only counted here, and
        // what comes out is that many "../" - so nothing of it can be text the CSS reader would have to escape
        StringBuilder relative = new StringBuilder("../../");
        if ( logicalResource != null && logicalResource.length() > 0 )
        {
            String tmpRelative = StringUtils.replace( logicalResource, "\\", "/" );
            for (int i=0;i<tmpRelative.split("/").length;i++) 
            {
                relative.append("../");
            }
        }
        writer.println( ".file{background:url(" + relative.toString() + "images/package-x-generic.png) no-repeat scroll 0 0 transparent;}" );
        writer.println( ".folder{background:url(" + relative.toString() + "images/folder.png) no-repeat scroll 0 0 transparent;}" );
        writer.println( "a{color:#0088CC;text-decoration: none;padding-left:20px;}" );
        writer.println( ".collection tr:nth-child(odd){background-color:#fafafa;}" );
        writer.println( "tr td:nth-child(2){width:150px;color:#cc8800;text-align:right;}" );
        writer.println( "tr td:nth-child(3){width:150px;color:#0000cc;text-align:center;}" );
        writer.println( "th td:nth-child(2){width:150px;}" );
        writer.println( "th td:nth-child(3){width:150px;}" );
        writer.println( "</style>" );
        writer.println( "<link rel=\"shortcut icon\" href=\"../../favicon.ico\"/>" );
        writer.println( "</head>" );
        writer.println( "<body>" );
        writer.println( "<h3>" + text( "Collection: /" + logicalResource ) + "</h3>" );

        //Check if not root
        if ( logicalResource != null && logicalResource.length() > 0 )
        {
            Path file = Paths.get( logicalResource );
            String parentName = file.getParent() == null ? "/" : file.getParent().toString();

            //convert to unix path in case archiva is hosted on windows
            parentName = StringUtils.replace( parentName, "\\", "/" );

            writer.println( "<ul>" );
            writer.println( "<li><a class=\"folder\" href=\"../\">" + text( parentName )
                + "</a> <i><small>(Parent)</small></i></li>" );
            writer.println( "</ul>" );
        }

        writer.println( "<table class=\"collection\">" );
        writer.println( "<tr><th>Name</th><th>Size (Bytes)</th><th>Last Modified</th></tr>" );
    }

    private void writeDocumentEnd( PrintWriter writer )
    {
        writer.println( "</table>" );
        writer.println( "</body>" );
        writer.println( "</html>" );
    }

    private void writeHyperlinks( PrintWriter writer ) throws IOException
    {
        if ( !isVirtual )
        {
            for ( StorageAsset localResource : repositoryAssets )
            {
                localResource.list().stream().sorted(
                    Comparator.comparing( StorageAsset::getName )
                ).forEach( asset -> {
                    writeHyperlink( writer, asset.getName(), asset.getModificationTime().toEpochMilli(), asset.getSize(),
                        asset.isContainer() );
                } );
            }
        }
        else
        {
            // virtual repository - filter unique directories
            SortedMap<String, StorageAsset> uniqueChildFiles = new TreeMap<>();
            for ( StorageAsset resource : repositoryAssets )
            {
                List<? extends StorageAsset> files = resource.list();
                for ( StorageAsset file : files )
                {
                    // the first entry wins
                    if (!uniqueChildFiles.containsKey( file.getName() )) {
                        uniqueChildFiles.put(file.getName(), file);
                    }
                }
            }
            for ( Map.Entry<String, StorageAsset> entry : uniqueChildFiles.entrySet())
            {
                final StorageAsset asset = entry.getValue();
                 writeHyperlink( writer, asset.getName(), asset.getModificationTime().toEpochMilli(),
                            asset.getSize(), asset.isContainer());
            }
        }
    }

    private static String fileDateFormat( long date ) 
    {
        DateFormat dateFormatter = DateFormat.getDateTimeInstance( DateFormat.SHORT, DateFormat.SHORT, Locale.getDefault() );
        Date aDate = new Date( date );
        return dateFormatter.format( aDate );
    }
    
    private void writeHyperlink( PrintWriter writer, String resourceName, long lastModified, long fileSize, boolean directory )
    {
        // the name twice over, in two different languages: percent-encoded where it is a target, escaped where it
        // is text. Both are the same name, and neither form is safe in the other's place.
        String target = href( resourceName );
        String label = text( resourceName );

        if ( directory )
        {
            writer.println( "<tr><td><a class=\"folder\" href=\"" + target + "/\">" + label + "</a></td><td>&nbsp;</td><td>&nbsp;</td></tr>" );
        }
        else
        {
            writer.println( "<tr><td><a class=\"file\" href=\"" + target + "\">" + label + "</a></td><td class=\"size\">" + fileSize + "&nbsp;&nbsp;</td><td class=\"date\">" + fileDateFormat( lastModified ) + "</td></tr>" );
        }
    }

    /**
     * A value as the page reads it: a name, a path, a date - never a tag. Escaping at the point of output is what
     * keeps a value safe whatever it arrives from, including a directory name a deployer created on purpose.
     */
    private static String text( String value )
    {
        return StringEscapeUtils.escapeHtml4( value );
    }

    /**
     * A name as the target of a link, which needs more than escaping: an escaped value can still be a scheme. A
     * file called {@code javascript:alert(1).html} is a name, but written into an {@code href} unchanged it is a
     * click away from running, and {@code data:} and {@code view-source:} are the same story.
     *
     * <p>Encoding every character a path segment is not made of leaves one reading available, a relative path.
     * URLEncoder is for query strings, so the two differences that would show are corrected: a space it writes as
     * {@code +} - which a server reads as a plus - becomes {@code %20}, and a colon it leaves alone, being legal
     * in a path, becomes {@code %3A}, being what makes a reference absolute. A literal {@code +} is already
     * encoded by the time either runs, so this round-trips: what the listing links to is the file it listed.
     */
    private static String href( String name )
    {
        return URLEncoder.encode( name, StandardCharsets.UTF_8 )
                         .replace( "+", "%20" )
                         .replace( ":", "%3A" );
    }
}
