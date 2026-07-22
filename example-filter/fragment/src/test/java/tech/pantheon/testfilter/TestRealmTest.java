/*
 * Copyright (c) 2026 PANTHEON.tech, s.r.o. and others.  All rights reserved.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/epl-v10.html
 */
package tech.pantheon.testfilter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Set;
import org.apache.shiro.authc.AuthenticationException;
import org.apache.shiro.authc.UsernamePasswordToken;
import org.apache.shiro.subject.SimplePrincipalCollection;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.opendaylight.aaa.api.shiro.principal.ODLPrincipal;
import org.opendaylight.aaa.shiro.filters.Oauth2ProxyHeaderToken;
import tech.pantheon.testfilter.config.TestFilterConfig.Mode;
import tech.pantheon.testfilter.config.TestFilterConfigHolder;

class TestRealmTest {
    private final TestRealm realm = new TestRealm();

    @AfterEach
    void afterEach() {
        TestFilterConfigHolder.reset();
    }

    /**
     * In {@code allow-all} mode any username/password combination authenticates and the resulting
     * {@link ODLPrincipal} carries the username as its only role — user {@code bob} gets role {@code bob}.
     */
    @Test
    void allowAllAuthenticates() {
        setMode(Mode.ALLOW_ALL);

        final var info = realm.doGetAuthenticationInfo(new UsernamePasswordToken("bob", "whatever"));

        final var principal = assertInstanceOf(ODLPrincipal.class, info.getPrincipals().getPrimaryPrincipal());
        assertEquals("bob", principal.getUsername());
        assertEquals("bob", principal.getUserId());
        assertEquals(Set.of("bob"), principal.getRoles());
    }

    /**
     * The literal requested scenario: {@code admin:admin} credentials authenticate in {@code allow-all} mode
     * and the user is granted the {@code admin} role derived from the username.
     */
    @Test
    void adminGetsAdminRole() {
        setMode(Mode.ALLOW_ALL);

        final var info = realm.doGetAuthenticationInfo(new UsernamePasswordToken("admin", "admin"));

        final var principal = assertInstanceOf(ODLPrincipal.class, info.getPrincipals().getPrimaryPrincipal());
        assertEquals("admin", principal.getUsername());
        assertEquals(Set.of("admin"), principal.getRoles());
    }

    /**
     * In {@code reject-all} mode every authentication attempt is rejected with an
     * {@link AuthenticationException}, even for otherwise valid credentials.
     */
    @Test
    void rejectAllRejects() {
        setMode(Mode.REJECT_ALL);

        assertThrows(AuthenticationException.class,
            () -> realm.doGetAuthenticationInfo(new UsernamePasswordToken("admin", "admin")));
    }

    /**
     * Before any configuration is published to {@code TestFilterConfigHolder} the realm fails closed:
     * authentication is rejected as if {@code reject-all} were configured.
     */
    @Test
    void failsClosedWithoutConfiguration() {
        assertThrows(AuthenticationException.class,
            () -> realm.doGetAuthenticationInfo(new UsernamePasswordToken("admin", "admin")));
    }

    /**
     * A token without a usable username (blank) is rejected even in {@code allow-all} mode — there is no
     * identity to authenticate and no role to derive.
     */
    @Test
    void blankUsernameIsRejected() {
        setMode(Mode.ALLOW_ALL);

        assertThrows(AuthenticationException.class,
            () -> realm.doGetAuthenticationInfo(new UsernamePasswordToken("", "")));
    }

    /**
     * The realm only consumes {@link UsernamePasswordToken}s: an {@link Oauth2ProxyHeaderToken} is not
     * supported here and is instead routed by Shiro to aaa's {@code Oauth2ProxyHeaderRealm}.
     */
    @Test
    void oauth2TokenIsNotSupported() {
        assertFalse(realm.supports(new Oauth2ProxyHeaderToken(Set.of("admin"), "alice")));
    }

    /**
     * Authorization reads the roles back from the authenticated {@link ODLPrincipal}: the role set granted at
     * authentication time (the username) is exactly what authorization checks see.
     */
    @Test
    void authorizationEchoesPrincipalRoles() {
        setMode(Mode.ALLOW_ALL);

        final var info = realm.doGetAuthenticationInfo(new UsernamePasswordToken("bob", "whatever"));
        final var principals = new SimplePrincipalCollection(info.getPrincipals().getPrimaryPrincipal(), "TestRealm");

        assertEquals(Set.of("bob"), realm.doGetAuthorizationInfo(principals).getRoles());
    }

    private static void setMode(final Mode mode) {
        TestFilterConfigHolder.set(() -> mode);
    }
}
