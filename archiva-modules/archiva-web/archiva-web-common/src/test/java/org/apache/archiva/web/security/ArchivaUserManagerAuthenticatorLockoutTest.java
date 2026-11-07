package org.apache.archiva.web.security;

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

import org.apache.archiva.redback.authentication.AuthenticationException;
import org.apache.archiva.redback.authentication.AuthenticationResult;
import org.apache.archiva.redback.authentication.PasswordBasedAuthenticationDataSource;
import org.apache.archiva.redback.policy.AccountLockedException;
import org.apache.archiva.redback.policy.MustChangePasswordException;
import org.apache.archiva.redback.policy.PasswordEncoder;
import org.apache.archiva.redback.policy.UserSecurityPolicy;
import org.apache.archiva.redback.users.User;
import org.apache.archiva.redback.users.UserManager;
import org.apache.archiva.redback.users.UserNotFoundException;
import org.junit.Before;
import org.junit.Test;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * What {@link ArchivaUserManagerAuthenticator#authenticate} decides about an account it has just been told may not
 * sign in.
 *
 * <p>This authenticator is what answers an {@code Authorization: Basic} header: the WebDAV servlet, the RSS servlet
 * and the browse endpoints take the credentials a stranger sent, hand them here, and act on the answer. Whatever this
 * method returns <em>is</em> the login decision on those paths.
 *
 * <p>The locked-account branch and the expired-password branch each carry a commented-out {@code //throw}, and both
 * now only record their complaint in a local variable and fall through. The next thing that happens when the
 * password turns out to be right is {@code return new AuthenticationResult( true, username, null )} - a fresh object
 * mentioning neither the recorded exception nor the collected failure causes. So a lock, which is the control that
 * exists to stop a password guessing run, stops nothing: the correct password still gets in, and the counter that
 * recorded the attempt is zeroed on the way, erasing the evidence at the moment it is most wanted.
 *
 * <p>Refusals are asserted as refusals rather than as one particular exception, because the signature declares
 * {@code AccountLockedException} and {@code MustChangePasswordException} - which is what Redback's own
 * {@code UserManagerAuthenticator} throws for these two states - while an unsuccessful result carrying the cause
 * would be an equally correct answer. What none of these tests will accept is a grant. The account in good standing
 * is here to keep the fix honest: refusing everything passes the hostile cases for the wrong reason.
 *
 * @see <a href="https://github.com/alsutton/archiva/issues/72">issue #72</a>
 */
public class ArchivaUserManagerAuthenticatorLockoutTest
{
    private static final String USERNAME = "payroll-clerk";

    /** The password this account actually has. */
    private static final String PASSWORD = "corr3ct-horse-battery";

    /** How the account stores it, and what the encoder below agrees is valid for {@link #PASSWORD}. */
    private static final String ENCODED_PASSWORD = "sha256:" + PASSWORD;

    /** What the account's failed attempt counter reads on arrival: a run nobody has looked at yet. */
    private static final int OPEN_FAILED_ATTEMPTS = 4;

    /** The account the manager will find. */
    private final Account account = new Account();

    /** Calls the manager was asked to make, in order. */
    private final List<String> managerCalls = new ArrayList<>();

    /** Policy hooks that ran, in order. */
    private final List<String> policyCalls = new ArrayList<>();

    private ArchivaUserManagerAuthenticator authenticator;

    @Before
    public void setUp()
        throws Exception
    {
        authenticator = new ArchivaUserManagerAuthenticator();
        inject( authenticator, "securityPolicy", securityPolicy() );
        inject( authenticator, "userManagers",
                Collections.singletonList( recordingManager( "memory", account, managerCalls ) ) );
    }

    // ---------------------------------------------------------------------------------------------
    // the two states that mean "not yet"
    // ---------------------------------------------------------------------------------------------

    /**
     * A locked account presenting the right password. Locking is what {@code extensionExcessiveLoginAttempts} does
     * once attempts pile up, and it is the last defence behind a leaked password: it has to outrank "the password was
     * correct".
     */
    @Test
    public void aLockedAccountIsNotAuthenticatedEvenThoughItsPasswordIsCorrect()
        throws Exception
    {
        account.locked = true;

        assertRefused( PASSWORD );
    }

    /**
     * The same account locked and the password wrong: still no session. That case refuses for a different reason,
     * and a locked account must not be able to trade one answer for the other.
     */
    @Test
    public void aLockedAccountIsNotAuthenticatedWithTheWrongPasswordEither()
        throws Exception
    {
        account.locked = true;

        assertRefused( PASSWORD + "-typo" );
    }

    /**
     * An account whose password has expired and whose caller wants that enforced - which is every caller, since
     * {@code PasswordBasedAuthenticationDataSource.isEnforcePasswordChange()} is a constant {@code true}. "May read,
     * but may not work until it is changed" is the state that branch exists to produce; a session is not.
     */
    @Test
    public void anExpiredPasswordIsNotAuthenticatedWhenItMustBeChangedFirst()
        throws Exception
    {
        account.passwordChangeRequired = true;

        assertRefused( PASSWORD );
    }

    /**
     * A refusal clears nothing. The failed attempt count is both what trips the lock and the record of the run that
     * tripped it; answering the run with a grant sets it back to zero, so the next run starts clean and whatever
     * reads the account afterwards finds nobody trying.
     */
    @Test
    public void aRefusedAccountKeepsItsCountOfFailedAttempts()
        throws Exception
    {
        account.locked = true;

        assertRefused( PASSWORD );

        assertEquals( "the account kept the count it came in with", OPEN_FAILED_ATTEMPTS, account.failedAttempts );
        assertFalse( "nothing should have been written back: " + managerCalls,
                     managerCalls.contains( "updateUser" ) );
    }

    // ---------------------------------------------------------------------------------------------
    // the accounts that were never in trouble, so that the tests above mean something
    // ---------------------------------------------------------------------------------------------

    /**
     * An account in good standing is authenticated, its counter is cleared and the expiry policy is consulted, as
     * they have always been.
     */
    @Test
    public void anAccountInGoodStandingIsAuthenticated()
        throws Exception
    {
        AuthenticationResult result = authenticator.authenticate( credentials( PASSWORD ) );

        assertTrue( "an account in good standing was refused: " + result, result.isAuthenticated() );
        assertEquals( USERNAME, result.getPrincipal() );
        assertEquals( "its failed attempt count was not cleared", 0, account.failedAttempts );
        assertTrue( "the cleared count should have been written back: " + managerCalls,
                    managerCalls.contains( "updateUser" ) );
    }

    /**
     * An ordinary failed login: refused, no exception, and the policy asked to count the attempt.
     */
    @Test
    public void aWrongPasswordIsRefusedWithoutCeremony()
        throws Exception
    {
        AuthenticationResult result = authenticator.authenticate( credentials( "not-the-password" ) );

        assertFalse( "a wrong password was accepted", result.isAuthenticated() );
        assertTrue( "the policy was never asked to count the attempt", policyCalls.contains( "excessiveAttempts" ) );
    }

    /**
     * A principal no manager knows is refused, and the refusal is a decision rather than a stray
     * {@link NullPointerException} on the way to one.
     */
    @Test
    public void aPrincipalNobodyKnowsIsRefused()
        throws Exception
    {
        account.known = false;

        AuthenticationResult result = authenticator.authenticate( credentials( PASSWORD ) );

        assertFalse( "an account that does not exist was accepted", result.isAuthenticated() );
    }

    // ---------------------------------------------------------------------------------------------
    // the pieces
    // ---------------------------------------------------------------------------------------------

    /**
     * Hands over the credentials and insists they were not accepted. Either way of refusing is allowed - the
     * exception the signature declares, which is what Redback's own authenticator throws for these states, or an
     * unsuccessful result. A grant is what ends the test.
     */
    private void assertRefused( String password )
        throws Exception
    {
        try
        {
            AuthenticationResult result = authenticator.authenticate( credentials( password ) );
            assertFalse( "the credentials were accepted: " + result, result.isAuthenticated() );
        }
        catch ( AccountLockedException | MustChangePasswordException | AuthenticationException refused )
        {
            // refused, which is the point
        }
    }

    private PasswordBasedAuthenticationDataSource credentials( String password )
    {
        return new PasswordBasedAuthenticationDataSource( USERNAME, password );
    }

    /** The account, as the manager found it. */
    private static final class Account
    {
        private boolean known = true;

        private boolean locked;

        private boolean passwordChangeRequired;

        private int failedAttempts = OPEN_FAILED_ATTEMPTS;
    }

    /** A {@link UserManager} that knows the one account it was given, and writes back what it is told to. */
    private static UserManager recordingManager( String id, Account account, List<String> calls )
    {
        return (UserManager) Proxy.newProxyInstance(
            ArchivaUserManagerAuthenticatorLockoutTest.class.getClassLoader(),
            new Class<?>[] { UserManager.class }, recording( calls, ( name, args ) -> {
                switch ( name )
                {
                    case "findUser":
                        if ( !account.known )
                        {
                            throw new UserNotFoundException( "no such user: " + USERNAME );
                        }
                        return user( account );
                    case "getId":
                        return id;
                    case "isReadOnly":
                        return Boolean.FALSE;
                    case "updateUser":
                        return args[0];
                    default:
                        return fallback( name );
                }
            } ) );
    }

    /** A {@link User} whose security flags live in an {@link Account}. */
    private static User user( Account account )
    {
        return (User) Proxy.newProxyInstance( ArchivaUserManagerAuthenticatorLockoutTest.class.getClassLoader(),
                                             new Class<?>[] { User.class }, ( proxy, method, args ) -> {
            switch ( method.getName() )
            {
                case "getUsername":
                    return USERNAME;
                case "getEncodedPassword":
                    return ENCODED_PASSWORD;
                case "isLocked":
                    return account.locked;
                case "isPasswordChangeRequired":
                    return account.passwordChangeRequired;
                case "setPasswordChangeRequired":
                    account.passwordChangeRequired = (Boolean) args[0];
                    return null;
                case "getCountFailedLoginAttempts":
                    return account.failedAttempts;
                case "setCountFailedLoginAttempts":
                    account.failedAttempts = (Integer) args[0];
                    return null;
                default:
                    return fallback( method.getName() );
            }
        } );
    }

    /** A {@link UserSecurityPolicy} that expires nothing on its own, and hands out an encoder that knows one password. */
    private UserSecurityPolicy securityPolicy()
    {
        PasswordEncoder encoder = (PasswordEncoder) Proxy.newProxyInstance(
            ArchivaUserManagerAuthenticatorLockoutTest.class.getClassLoader(),
            new Class<?>[] { PasswordEncoder.class },
            ( proxy, method, args ) -> "isPasswordValid".equals( method.getName() )
                ? ENCODED_PASSWORD.equals( args[0] ) && PASSWORD.equals( args[1] )
                : fallback( method.getName() ) );

        return (UserSecurityPolicy) Proxy.newProxyInstance(
            ArchivaUserManagerAuthenticatorLockoutTest.class.getClassLoader(),
            new Class<?>[] { UserSecurityPolicy.class }, ( proxy, method, args ) -> {
                switch ( method.getName() )
                {
                    case "getPasswordEncoder":
                        return encoder;
                    case "extensionExcessiveLoginAttempts":
                        policyCalls.add( "excessiveAttempts" );
                        return null;
                    case "extensionPasswordExpiration":
                        policyCalls.add( "passwordExpiration" );
                        return null;
                    default:
                        return fallback( method.getName() );
                }
            } );
    }

    // ---------------------------------------------------------------------------------------------
    // plumbing shared by the fakes above
    // ---------------------------------------------------------------------------------------------

    /** What a fake does with a call it has not been told about. */
    private interface Handler
    {
        Object handle( String name, Object[] args )
            throws Throwable;
    }

    private static InvocationHandler recording( List<String> calls, Handler delegate )
    {
        return ( proxy, method, args ) -> {
            String name = method.getName();
            if ( "toString".equals( name ) )
            {
                return proxy.getClass().getInterfaces()[0].getSimpleName() + "@test";
            }
            if ( "hashCode".equals( name ) )
            {
                return System.identityHashCode( proxy );
            }
            if ( "equals".equals( name ) )
            {
                return proxy == args[0];
            }
            calls.add( name );
            return delegate.handle( name, args );
        };
    }

    /**
     * The answer a fake gives when the test has not said: {@code false} for the questions that answer in booleans -
     * which leaves an unmentioned manager writable and an unmentioned policy enabled - and {@code null} for the rest.
     */
    private static Object fallback( String methodName )
    {
        return methodName.startsWith( "is" ) || methodName.startsWith( "has" ) ? Boolean.FALSE : null;
    }

    static void inject( Object target, String fieldName, Object value )
        throws Exception
    {
        Field field = field( target.getClass(), fieldName );
        field.setAccessible( true );
        field.set( target, value );
    }

    private static Field field( Class<?> type, String fieldName )
    {
        for ( Class<?> c = type; c != null; c = c.getSuperclass() )
        {
            try
            {
                return c.getDeclaredField( fieldName );
            }
            catch ( NoSuchFieldException e )
            {
                // it belongs to a superclass, and the loop will get there
            }
        }
        throw new AssertionError( Arrays.toString( type.getDeclaredFields() ) + " has no field " + fieldName );
    }
}
