package org.apache.archiva.security;

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

import org.apache.archiva.redback.authentication.AuthenticationException;
import org.apache.archiva.redback.authentication.AuthenticationResult;
import org.junit.Before;
import org.junit.Test;

import java.lang.reflect.Proxy;

import javax.servlet.http.HttpServletRequest;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.fail;

/**
 * What {@link ArchivaServletAuthenticator#isAuthenticated} makes of a request it was handed no verdict on.
 *
 * <p>The method is the gate in front of the repository endpoints. {@code ArchivaDavSessionProvider.attachSession}
 * and {@code ArchivaDavResourceFactory.isAuthorized} both ask {@code httpAuth.getAuthenticationResult( request,
 * null )} what the credentials on the request amount to and pass the answer straight in here; {@code RssFeedServlet}
 * does the same before it lists anything. That question is answered with {@code null} whenever the request carries
 * nothing to authenticate - no {@code Authorization} header, no session - which is the commonest thing a stranger
 * on the network sends.
 *
 * <p>The condition is {@code if ( result != null && !result.isAuthenticated() )}. A missing verdict is not a failed
 * one, so it walks on through: {@code attachSession} returns {@code true} and hands the caller an open DavSession,
 * and the two other call sites carry on into {@code isAuthorized} with a session from {@code getSession( true )} -
 * an anonymous session, created for the occasion. Fail-open is the only possible outcome of asking "is this
 * definitely a failure?" when the question to ask is "is this definitely a success?".
 *
 * <p>The two controls are the answers the method already gets right, so that a fix that denies everything does not
 * look like a fix.
 *
 * @see <a href="https://github.com/alsutton/archiva/issues/73">issue #73</a>
 */
public class ArchivaServletAuthenticatorNullResultTest
{
    private ArchivaServletAuthenticator authenticator;

    private HttpServletRequest request;

    @Before
    public void setUp()
    {
        authenticator = new ArchivaServletAuthenticator();
        request = (HttpServletRequest) Proxy.newProxyInstance(
            getClass().getClassLoader(), new Class<?>[] { HttpServletRequest.class },
            ( proxy, method, args ) -> {
                if ( "toString".equals( method.getName() ) )
                {
                    return "request@test";
                }
                if ( "hashCode".equals( method.getName() ) )
                {
                    return System.identityHashCode( proxy );
                }
                if ( "equals".equals( method.getName() ) )
                {
                    return proxy == args[0];
                }
                return method.getReturnType() == boolean.class ? Boolean.FALSE : null;
            } );
    }

    /**
     * No verdict means nobody authenticated anybody, which is not the same as having tried and succeeded. This is
     * the request with no credentials at all.
     */
    @Test
    public void aRequestWithNoAuthenticationResultIsNotAuthenticated()
        throws Exception
    {
        AuthenticationException refused =
            assertThrows( AuthenticationException.class, () -> authenticator.isAuthenticated( request, null ) );

        assertTrue( "the refusal should say the credentials were the problem: " + refused.getMessage(),
                    refused.getMessage().contains( "Credential" ) );
    }

    /**
     * Control: a verdict that says no was already refused, which is the case the condition was written for.
     */
    @Test
    public void aFailedAuthenticationResultIsNotAuthenticated()
        throws Exception
    {
        AuthenticationResult failure = new AuthenticationResult( false, "payroll-clerk", null );

        try
        {
            authenticator.isAuthenticated( request, failure );
            fail( "an unsuccessful result was accepted" );
        }
        catch ( AuthenticationException expected )
        {
            // as it should be
        }
    }

    /**
     * Control: an authenticated principal still gets through, so the deny-by-default above is a denial of the
     * missing verdict and not of every verdict.
     */
    @Test
    public void aSuccessfulAuthenticationResultIsAuthenticated()
        throws Exception
    {
        AuthenticationResult success = new AuthenticationResult( true, "payroll-clerk", null );

        assertTrue( "an authenticated principal was turned away",
                    authenticator.isAuthenticated( request, success ) );
    }
}
