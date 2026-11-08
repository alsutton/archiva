package org.apache.archiva.metadata.repository.jcr;

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

import org.junit.Before;
import org.junit.Test;

import javax.jcr.NodeIterator;
import javax.jcr.RepositoryException;
import javax.jcr.Session;
import javax.jcr.Value;
import javax.jcr.ValueFactory;
import javax.jcr.Workspace;
import javax.jcr.query.Query;
import javax.jcr.query.QueryManager;
import javax.jcr.query.QueryResult;
import javax.jcr.query.RowIterator;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/**
 * The JCR metadata store is handed a single administrative session - {@code JcrMetadataRepository.login()} logs in
 * as {@code admin} - and answers a request about one repository by scoping a query to that repository's node path.
 * That scope is the entire boundary between repositories: below JCR, nothing knows that repositories exist.
 *
 * <p>Two things crossed it. The values a query asked about were written into the statement instead of bound into
 * it, so a value carrying a quote could append a scope of its own; and the repository identifier was written into
 * the scope path itself, so it did not even need a quote - a parenthesis or a {@code ..} was enough.
 *
 * <p>These tests pin the two rules that follow: a value is a bind variable and never text in a statement, and a
 * repository identifier is checked before it is allowed to name a path. Everything is asserted through the public
 * {@code getProjectReferences} - the method {@code DefaultBrowseService.getDependees} reaches with request path
 * segments - against a JCR session that records the statements it is handed instead of answering them. For a
 * repository that cannot exist, what matters is that nothing is handed over at all.
 *
 * @see <a href="https://github.com/alsutton/archiva/issues/68">issue #68</a>
 * @see <a href="https://github.com/alsutton/archiva/issues/69">issue #69</a>
 */
public class JcrMetadataRepositoryQueryScopeTest
{
    private static final String RELEASES = "releases";

    private static final String PAYROLL = "payroll-internal";

    private static final String GROUP_ID = "com.example.payroll";

    private static final String PROJECT_ID = "payroll-core";

    private static final String VERSION = "1.4.2";

    /**
     * A project id as it arrives from a request path segment: closes the literal it is being put inside, adds a
     * scope of its own, then opens a literal again so the rest of the statement still parses.
     */
    private static final String WIDENING_THE_SCOPE =
        "payroll' OR ISDESCENDANTNODE([/" + PAYROLL + "/content]) OR [artifactId]='x";

    /**
     * A repository identifier that would end the path it is written into and append a second scope. It is not a
     * repository any administration screen could have created, which is what makes refusing it cheap.
     */
    private static final String ESCAPING_THE_SCOPE = "releases]) OR ISDESCENDANTNODE([/" + PAYROLL;

    /**
     * Identifiers no repository can have been created with. Each would change the path a query is scoped to, and
     * so the repositories it covers; {@code null} and blank are here because a path that names no repository is
     * not a query about everything.
     */
    private static final List<String> NOT_REPOSITORY_IDENTIFIERS = Arrays.asList( null, "", "   ", "releases/content",
                                                                                   "../" + PAYROLL,
                                                                                   "repositories/" + PAYROLL,
                                                                                   "/" + PAYROLL,
                                                                                   ESCAPING_THE_SCOPE,
                                                                                   "releases' OR 1=1 --",
                                                                                   "releases\ndata",
                                                                                   "release;node=1",
                                                                                   "re releases" );

    private static final Pattern BIND_MARKER = Pattern.compile( "\\$([A-Za-z][A-Za-z0-9]*)" );

    /**
     * The statements the store handed to JCR, in order.
     */
    private final List<String> statements = new ArrayList<>();

    /**
     * The values it bound, by marker name.
     */
    private final Map<String, String> boundValues = new HashMap<>();

    /**
     * The values handed to {@code ValueFactory.createValue}, by the object they came back as: a bind variable
     * arrives at {@code bindValue} as an opaque {@link Value}, and what matters here is that the text reaches it
     * unchanged rather than being escaped, truncated or dropped on the way.
     */
    private final Map<Object, String> rawOf = new IdentityHashMap<>();

    private JcrRepositorySession repositorySession;

    @Before
    public void setUp()
        throws Exception
    {
        // the store reads its JCR session out of a JcrRepositorySession, which gets one by logging in
        repositorySession = new JcrRepositorySession( new RecordedLogin( (Session) recorded( Session.class ) ), null );
    }

    // ---------------------------------------------------------------------------------------------
    // #68: what a query asks about is bound into it, not written into it
    // ---------------------------------------------------------------------------------------------

    @Test
    public void theValuesAskedAboutAreBoundAndNotWrittenIntoTheStatement()
        throws Exception
    {
        references( RELEASES, GROUP_ID, PROJECT_ID, VERSION );

        String statement = theOneStatement();

        assertTrue( "the namespace is asked for by name: " + statement, statement.contains( "$namespace" ) );
        assertTrue( "the project is asked for by name: " + statement, statement.contains( "$projectId" ) );
        assertTrue( "the version is asked for by name: " + statement, statement.contains( "$projectVersion" ) );

        assertFalse( "a value in the statement is text the parser reads: " + statement,
                     statement.contains( GROUP_ID ) || statement.contains( PROJECT_ID )
                         || statement.contains( VERSION ) );

        Map<String, String> expected = new HashMap<>();
        expected.put( "namespace", GROUP_ID );
        expected.put( "projectId", PROJECT_ID );
        expected.put( "projectVersion", VERSION );
        assertEquals( expected, boundValues );
    }

    /**
     * The statement used to be built by concatenating these values, so a value containing a quote could give the
     * query a second repository to read. Asked about through the store, with JCR recording what it was handed, that
     * has to hold end to end.
     */
    @Test
    public void aQuoteInTheProjectIdStaysPartOfTheProjectId()
        throws Exception
    {
        List<?> references = references( RELEASES, GROUP_ID, WIDENING_THE_SCOPE, VERSION );

        assertTrue( "the caller is answered, not put off with a parse error", references.isEmpty() );

        String statement = theOneStatement();

        assertFalse( "a statement with no literal has nothing to escape: " + statement, statement.contains( "'" ) );
        assertFalse( "the only repository in scope is the one asked about: " + statement, statement.contains( PAYROLL ) );
        assertTrue( "scoped to the repository asked about: " + statement,
                    statement.contains( "ISDESCENDANTNODE([/repositories/" + RELEASES + "/content])" ) );

        // and the hostile text is still in the request, where it belongs: as the value of one bind variable
        assertEquals( WIDENING_THE_SCOPE, boundValues.get( "projectId" ) );
        assertEquals( GROUP_ID, boundValues.get( "namespace" ) );
        assertEquals( VERSION, boundValues.get( "projectVersion" ) );
    }

    /**
     * Asking without a version asks for every version, which used to be spelled by leaving a clause out of the
     * concatenation. The marker has to leave with the clause: a {@code $projectVersion} left standing in a
     * statement nobody binds is an error at JCR and an empty list at the caller, which is indistinguishable from a
     * repository with nothing in it.
     */
    @Test
    public void theVersionIsAskedForOnlyWhenOneWasGiven()
        throws Exception
    {
        references( RELEASES, GROUP_ID, PROJECT_ID, null );

        String statement = theOneStatement();

        assertFalse( "no version clause left over: " + statement, statement.contains( "[version]" ) );
        assertEquals( new LinkedHashSet<>( Arrays.asList( "namespace", "projectId" ) ),
                      new LinkedHashSet<>( boundValues.keySet() ) );

        // and asking with one asks for all three
        referencesWithFreshEyes( RELEASES, GROUP_ID, PROJECT_ID, VERSION );
        assertEquals( new LinkedHashSet<>( Arrays.asList( "namespace", "projectId", "projectVersion" ) ),
                      new LinkedHashSet<>( boundValues.keySet() ) );
    }

    /**
     * Statement and values agree with each other in both directions: every marker has a value, and every value has
     * a marker. Either way round, the mismatch is a query that fails or a value silently ignored.
     */
    @Test
    public void everyMarkerInTheStatementIsGivenAValueAndEveryValueHasAMarker()
        throws Exception
    {
        references( RELEASES, GROUP_ID, PROJECT_ID, VERSION );

        Set<String> markers = markersIn( theOneStatement() );

        assertFalse( "the values are markers: " + markers, markers.isEmpty() );
        assertEquals( "markers with no value, or a value with no marker", boundValues.keySet(), markers );
    }

    // ---------------------------------------------------------------------------------------------
    // #69: a repository identifier names a path, so it is checked before it names one
    // ---------------------------------------------------------------------------------------------

    /**
     * The check is an allow-list, so it has to keep accepting what the administration layer accepts: a repository
     * whose id was refused by the metadata store would be a repository Archiva could not browse at all.
     */
    @Test
    public void anOrdinaryRepositoryIsServedFromItself()
        throws Exception
    {
        references( "Maven-Central_2.0.DRCP", GROUP_ID, PROJECT_ID, VERSION );

        assertTrue( "scoped to the repository asked about, in full: " + theOneStatement(),
                    theOneStatement().contains( "ISDESCENDANTNODE([/repositories/Maven-Central_2.0.DRCP/content])" ) );
    }

    @Test
    public void anIdentifierThatIsNotAnIdentifierIsRefused()
    {
        for ( String repositoryId : NOT_REPOSITORY_IDENTIFIERS )
        {
            statements.clear();

            assertThrows( "must not become a path: [" + repositoryId + "]", IllegalArgumentException.class,
                          () -> references( repositoryId, GROUP_ID, PROJECT_ID, VERSION ) );

            assertEquals( "JCR was asked something about [" + repositoryId + "]: " + statements,
                          Collections.emptyList(), statements );
        }
    }

    /**
     * The one place paths are built is the one place the check lives, so refusing an identifier is what a query
     * about that identifier does - and JCR, which would have answered from any repository, is never asked.
     */
    @Test
    public void aRepositoryThatCannotExistIsRefusedBeforeJcrIsAsked()
    {
        IllegalArgumentException refused =
            assertThrows( IllegalArgumentException.class,
                          () -> references( ESCAPING_THE_SCOPE, GROUP_ID, PROJECT_ID, VERSION ) );

        assertTrue( "the refusal says what was wrong: " + refused.getMessage(),
                    refused.getMessage().contains( "Repository identifier rejected" ) );
        // refusing is a log line, and a log line is no place for whatever a caller happened to send
        assertFalse( "the refused identifier is not echoed: " + refused.getMessage(),
                     refused.getMessage().contains( PAYROLL ) || refused.getMessage().contains( "]" ) );
        assertEquals( "nothing was handed to JCR: " + statements, Collections.emptyList(), statements );
    }

    // ---------------------------------------------------------------------------------------------
    // the store, and the JCR it is shown
    // ---------------------------------------------------------------------------------------------

    private List<?> references( String repositoryId, String namespace, String projectId, String projectVersion )
        throws Exception
    {
        return new JcrMetadataRepository( null, null ).getProjectReferences( repositorySession, repositoryId,
                                                                              namespace, projectId, projectVersion );
    }

    private void referencesWithFreshEyes( String repositoryId, String namespace, String projectId,
                                          String projectVersion )
        throws Exception
    {
        statements.clear();
        boundValues.clear();
        references( repositoryId, namespace, projectId, projectVersion );
    }

    private String theOneStatement()
    {
        assertEquals( "exactly one statement was handed to JCR: " + statements, 1, statements.size() );
        return statements.get( 0 );
    }

    private Set<String> markersIn( String statement )
    {
        Set<String> markers = new LinkedHashSet<>();
        Matcher matcher = BIND_MARKER.matcher( statement );
        while ( matcher.find() )
        {
            markers.add( matcher.group( 1 ) );
        }
        return markers;
    }

    /**
     * A {@link JcrMetadataRepository} that hands out the recorded session instead of logging into a real repository.
     */
    private static final class RecordedLogin extends JcrMetadataRepository
    {
        private final Session jcrSession;

        RecordedLogin( Session jcrSession )
            throws RepositoryException
        {
            super( null, null );
            this.jcrSession = jcrSession;
        }

        @Override
        public Session login()
        {
            return jcrSession;
        }
    }

    /**
     * Every JCR object the store reaches for. One handler does them all, because the methods it calls do not
     * overlap between interfaces, and one map of answers, because what these tests are about is what was asked and
     * what was bound rather than what came back. A method that is not stubbed fails the test instead of answering
     * quietly, so none of these tests can pass by the store having stopped asking.
     */
    private Object recorded( Class<?> iface )
    {
        InvocationHandler recorder = new InvocationHandler()
        {
            private final Map<String, Function<Object[], Object>> answers = answers();

            @Override
            public Object invoke( Object proxy, Method method, Object[] args )
            {
                switch ( method.getName() )
                {
                    case "toString":
                        return iface.getSimpleName() + " (recording)";
                    case "hashCode":
                        return System.identityHashCode( proxy );
                    case "equals":
                        return proxy == args[0];
                    default:
                        break;
                }

                Function<Object[], Object> answer = answers.get( method.getName() );
                if ( answer == null )
                {
                    throw new AssertionError( "unexpected call: " + iface.getSimpleName() + "." + method.getName()
                        + " - stub it, or say why the store should not be asking" );
                }
                return answer.apply( args );
            }
        };

        return Proxy.newProxyInstance( getClass().getClassLoader(), new Class<?>[] { iface }, recorder );
    }

    private Map<String, Function<Object[], Object>> answers()
    {
        Map<String, Function<Object[], Object>> answers = new HashMap<>();

        // Session -> Workspace -> QueryManager -> Query
        answers.put( "getWorkspace", args -> recorded( Workspace.class ) );
        answers.put( "getQueryManager", args -> recorded( QueryManager.class ) );
        answers.put( "createQuery", args -> {
            statements.add( (String) args[0] );
            return recorded( Query.class );
        } );
        answers.put( "setLimit", args -> null );
        answers.put( "setOffset", args -> null );
        answers.put( "execute", args -> recorded( QueryResult.class ) );

        // the bind variables, which is all a value is allowed to become
        answers.put( "getValueFactory", args -> recorded( ValueFactory.class ) );
        answers.put( "createValue", args -> {
            Value value = (Value) recorded( Value.class );
            rawOf.put( value, (String) args[0] );
            return value;
        } );
        answers.put( "bindValue", args -> {
            boundValues.put( (String) args[0], rawOf.get( args[1] ) );
            return null;
        } );

        // answers: empty, since what comes back is not what is under test
        answers.put( "getNodes", args -> recorded( NodeIterator.class ) );
        answers.put( "getRows", args -> recorded( RowIterator.class ) );
        answers.put( "hasNext", args -> Boolean.FALSE );
        answers.put( "getSize", args -> 0L );

        return answers;
    }
}
