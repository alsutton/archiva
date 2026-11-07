package org.apache.archiva.webdav;

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

import org.apache.archiva.redback.authentication.AuthenticationResult;
import org.apache.archiva.redback.integration.filter.authentication.HttpAuthenticator;
import org.apache.archiva.security.ServletAuthenticator;
import org.apache.jackrabbit.webdav.DavSession;
import org.apache.jackrabbit.webdav.WebdavRequest;
import org.junit.Before;
import org.junit.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * When {@link ArchivaDavSessionProvider#attachSession} puts a {@link DavSession} on a request.
 *
 * <p>This is the gate every WebDAV method goes through, and the session it attaches is what the rest of the stack
 * reads as "this request is signed in" - it outlives the boolean, since a {@code DavSession} attached to the request
 * is what the resource layer looks for later in the same request. It used to be attached before the decision was
 * taken, on the way to asking {@link ServletAuthenticator} whether there was one, so a refused request kept a
 * session it was never entitled to and whatever read it next had no way to tell the difference.
 *
 * <p>What these tests pin is the order, not the decision: the session follows the answer. The provider is wired to
 * an authenticator that refuses anything without a verdict, which is the real thing - the HTTP authenticator
 * answers {@code null} for a request carrying no credentials, and issue #73 is about what that answer means.
 *
 * @see <a href="https://github.com/alsutton/archiva/issues/73">issue #73</a>
 */
public class ArchivaDavSessionProviderAttachTest
{
    /** The sessions the provider attached, in order, including the {@code null} of a release. */
    private final List<DavSession> attached = new ArrayList<>();

    /** The verdict the HTTP layer is made to hand over. */
    private AuthenticationResult credentials;

    private WebdavRequest request;

    private ArchivaDavSessionProvider provider;

    @Before
    public void setUp()
    {
        attached.clear();
        credentials = null;
        provider = new ArchivaDavSessionProvider( refusingUnlessThereIsAResult(), new HttpAuthenticatorStub() );
        request = request( attached );
    }

    /**
     * A request that carried nothing to authenticate. Whether or not a session is attached to it afterwards is the
     * whole difference between a refused request and an anonymous one that the resource layer trusts.
     */
    @Test
    public void aRequestNobodyAuthenticatedIsLeftWithoutASession()
        throws Exception
    {
        assertFalse( "a request with no credentials was attached", provider.attachSession( request ) );

        assertNothingAttached();
    }

    /**
     * A verdict that says no: the same. Refusing is not the same as not having decided yet, but both leave the
     * request with nothing.
     */
    @Test
    public void aRequestWhoseCredentialsFailedIsLeftWithoutASession()
        throws Exception
    {
        credentials = new AuthenticationResult( false, "payroll-clerk", null );

        assertFalse( "failed credentials were attached", provider.attachSession( request ) );

        assertNothingAttached();
    }

    /**
     * Control: an authenticated principal is the case a DavSession exists for, and it still gets one.
     */
    @Test
    public void anAuthenticatedRequestIsGivenItsSession()
        throws Exception
    {
        credentials = new AuthenticationResult( true, "payroll-clerk", null );

        assertTrue( "an authenticated request was turned away", provider.attachSession( request ) );

        assertEquals( "exactly one session, attached once", 1, attached.size() );
    }

    private void assertNothingAttached()
    {
        assertEquals( "a session was attached to a request nobody authenticated", 0, attached.size() );
    }

    // ---------------------------------------------------------------------------------------------
    // the pieces
    // ---------------------------------------------------------------------------------------------

    /** The HTTP layer, answering with whatever this test wants the request to have been authenticated with. */
    private final class HttpAuthenticatorStub
        extends HttpAuthenticator
    {
        @Override
        public void challenge( HttpServletRequest req, HttpServletResponse res, String authHeader,
                               org.apache.archiva.redback.authentication.AuthenticationException e )
        {
            // nothing to challenge with: these tests never ask for a 401
        }

        @Override
        public AuthenticationResult getAuthenticationResult( HttpServletRequest req, HttpServletResponse res )
        {
            return credentials;
        }
    }

    /**
     * A {@link ServletAuthenticator} that authenticates a request with a verdict that says yes, which is the
     * behaviour every caller of this provider is relying on.
     */
    private static ServletAuthenticator refusingUnlessThereIsAResult()
    {
        return (ServletAuthenticator) Proxy.newProxyInstance(
            ArchivaDavSessionProviderAttachTest.class.getClassLoader(),
            new Class<?>[] { ServletAuthenticator.class }, ( proxy, method, args ) -> {
                switch ( method.getName() )
                {
                    case "isAuthenticated":
                        return args[1] != null && ( (AuthenticationResult) args[1] ).isAuthenticated();
                    case "isAuthorized":
                        return Boolean.TRUE;
                    default:
                        return answer( proxy, method.getName(), args );
                }
            } );
    }

    /**
     * A {@link WebdavRequest} for {@code GET /archiva/repository/payroll/payroll-core-1.0.jar}, keeping every session
     * it is given so the test can see whether one was attached and when.
     */
    private static WebdavRequest request( List<DavSession> attached )
    {
        return (WebdavRequest) Proxy.newProxyInstance( ArchivaDavSessionProviderAttachTest.class.getClassLoader(),
                                                      new Class<?>[] { WebdavRequest.class },
                                                      ( proxy, method, args ) -> {
                                                          switch ( method.getName() )
                                                          {
                                                              case "getRequestURI":
                                                                  return "/archiva/repository/payroll/payroll-core-1.0.jar";
                                                              case "getContextPath":
                                                                  return "/archiva";
                                                              case "getMethod":
                                                                  return "GET";
                                                              case "setDavSession":
                                                                  attached.add( (DavSession) args[0] );
                                                                  return null;
                                                              case "getDavSession":
                                                                  return attached.isEmpty()
                                                                      ? null
                                                                      : attached.get( attached.size() - 1 );
                                                              default:
                                                                  return answer( proxy, method.getName(), args );
                                                          }
                                                      } );
    }

    /** The answers every object owes, then {@code false} for booleans and {@code null} for the rest. */
    private static Object answer( Object proxy, String methodName, Object[] args )
    {
        if ( "toString".equals( methodName ) )
        {
            return proxy.getClass().getInterfaces()[0].getSimpleName() + "@test";
        }
        if ( "hashCode".equals( methodName ) )
        {
            return System.identityHashCode( proxy );
        }
        if ( "equals".equals( methodName ) )
        {
            return proxy == args[0];
        }
        return methodName.startsWith( "is" ) || methodName.startsWith( "has" ) ? Boolean.FALSE : null;
    }
}
