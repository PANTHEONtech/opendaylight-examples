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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.verify;
import static org.opendaylight.aaa.shiro.filters.Oauth2ProxyHeaderParserConfig.ALLOWED_CHARS_DEFAULT;
import static org.opendaylight.aaa.shiro.filters.Oauth2ProxyHeaderParserConfig.MAX_HEADER_LENGTH_DEFAULT;
import static org.opendaylight.aaa.shiro.filters.Oauth2ProxyHeaderParserConfig.MAX_ROLES_PER_USER_DEFAULT;
import static org.opendaylight.aaa.shiro.filters.Oauth2ProxyHeaderParserConfig.MAX_ROLE_LENGTH_DEFAULT;
import static org.opendaylight.aaa.shiro.filters.Oauth2ProxyHeaderParserConfig.MAX_USER_LENGTH_DEFAULT;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.apache.shiro.authc.UsernamePasswordToken;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.opendaylight.aaa.shiro.filters.Oauth2ProxyHeaderParser;
import org.opendaylight.aaa.shiro.filters.Oauth2ProxyHeaderParserConfig;
import org.opendaylight.aaa.shiro.filters.Oauth2ProxyHeaderParserImpl;
import org.opendaylight.aaa.shiro.filters.Oauth2ProxyHeaderToken;

@ExtendWith(MockitoExtension.class)
class TestFilterTest {
    /**
     * Minimal {@link Oauth2ProxyHeaderParserConfig} for tests; pattern derivation is handled by the interface's
     * own defaults.
     */
    private record TestConfig(int maxUserLength, String allowedChars) implements Oauth2ProxyHeaderParserConfig {
        @Override
        public int maxHeaderLength() {
            return MAX_HEADER_LENGTH_DEFAULT;
        }

        @Override
        public int maxRoleLength() {
            return MAX_ROLE_LENGTH_DEFAULT;
        }

        @Override
        public int maxRolesPerUser() {
            return MAX_ROLES_PER_USER_DEFAULT;
        }
    }

    private static final String AUTHORIZATION_HEADER = "Authorization";
    private static final String FORWARDED_USER_HEADER = "X-Forwarded-User";
    private static final String FORWARDED_GROUPS_HEADER = "X-Forwarded-Groups";
    private static final String BASIC_ADMIN_ADMIN = "Basic "
        + Base64.getEncoder().encodeToString("admin:admin".getBytes(StandardCharsets.UTF_8));

    private final TestFilter filter = new TestFilter();

    @Mock
    private HttpServletRequest request;
    @Mock
    private HttpServletResponse response;

    @BeforeEach
    void publishDefaultParser() {
        setParser(MAX_USER_LENGTH_DEFAULT, ALLOWED_CHARS_DEFAULT);
    }

    @AfterEach
    void resetParserSupplier() {
        TestFilter.parserSupplier = TestFilter.OSGI_LOOKUP;
    }

    /**
     * A request carrying {@code Authorization: Basic} is turned into a stock {@link UsernamePasswordToken}
     * with the decoded username and password, so it is routed to {@code TestRealm}.
     */
    @Test
    void basicAuthorizationCreatesUsernamePasswordToken() {
        doReturn(BASIC_ADMIN_ADMIN).when(request).getHeader(AUTHORIZATION_HEADER);

        final var token = assertInstanceOf(UsernamePasswordToken.class, filter.createToken(request, response));
        assertEquals("admin", token.getUsername());
        assertEquals("admin", String.valueOf(token.getPassword()));
    }

    /**
     * Without a Basic header, a request carrying OAuth2-Proxy forwarded headers is turned into aaa's
     * {@link Oauth2ProxyHeaderToken} by {@link Oauth2ProxyHeaderParser}: groups are split on commas and
     * stripped of the {@code role:} prefix.
     */
    @Test
    void forwardedHeadersCreateOauth2ProxyHeaderToken() {
        doReturn(null).when(request).getHeader(AUTHORIZATION_HEADER);
        stubForwardedHeader(FORWARDED_USER_HEADER, "alice");
        stubForwardedHeader(FORWARDED_GROUPS_HEADER, "role:global-admin, user, role:odl-application:admin");

        final var token = filter.createToken(request, response);

        assertEquals(new Oauth2ProxyHeaderToken(Set.of("global-admin", "user", "odl-application:admin"), "alice"),
            token);
    }

    /**
     * When both a Basic {@code Authorization} header and forwarded headers are present, Basic takes
     * precedence: a {@link UsernamePasswordToken} is created, not an {@code Oauth2ProxyHeaderToken}.
     */
    @Test
    void basicAuthorizationWinsOverForwardedHeaders() {
        doReturn(BASIC_ADMIN_ADMIN).when(request).getHeader(AUTHORIZATION_HEADER);

        assertInstanceOf(UsernamePasswordToken.class, filter.createToken(request, response));
    }

    /**
     * A request carrying neither a Basic {@code Authorization} header nor forwarded headers is not a login
     * attempt: it is challenged with 401 and {@code WWW-Authenticate: BASIC}, and filter processing stops.
     */
    @Test
    void missingHeadersSendChallenge() throws Exception {
        doReturn(null).when(request).getHeader(AUTHORIZATION_HEADER);
        stubForwardedHeader(FORWARDED_USER_HEADER);

        assertFalse(filter.onAccessDenied(request, response));
        verify(response).setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        verify(response).setHeader(startsWith("WWW-Authenticate"), startsWith("BASIC"));
    }

    /**
     * Login-attempt detection matrix: a Basic header counts, a valid {@code X-Forwarded-User} counts, and a
     * blank-only {@code X-Forwarded-User} is rejected by the parser (it falls through to the 401 challenge).
     */
    @Test
    void isLoginAttemptMatrix() {
        doReturn(BASIC_ADMIN_ADMIN).when(request).getHeader(AUTHORIZATION_HEADER);
        assertTrue(filter.isLoginAttempt(request, response));

        doReturn(null).when(request).getHeader(AUTHORIZATION_HEADER);
        stubForwardedHeader(FORWARDED_USER_HEADER, "alice");
        assertTrue(filter.isLoginAttempt(request, response));

        stubForwardedHeader(FORWARDED_USER_HEADER, " ");
        assertFalse(filter.isLoginAttempt(request, response));
    }

    /**
     * The {@code max-user-length} limit from the shared OAuth2-Proxy configuration is enforced when parsing
     * the forwarded user: with the limit lowered to 4, {@code alice} (5 characters) is rejected while
     * {@code bob} still authenticates.
     */
    @Test
    void sharedConfigMaxUserLengthEnforced() {
        setParser(4, ALLOWED_CHARS_DEFAULT);
        doReturn(null).when(request).getHeader(AUTHORIZATION_HEADER);

        stubForwardedHeader(FORWARDED_USER_HEADER, "alice");
        assertFalse(filter.isLoginAttempt(request, response));

        stubForwardedHeader(FORWARDED_USER_HEADER, "bob");
        assertTrue(filter.isLoginAttempt(request, response));
    }

    /**
     * The {@code allowed-chars} whitelist from the shared OAuth2-Proxy configuration is enforced when parsing
     * the forwarded user: {@code al!ce} is rejected under the default whitelist and accepted once the
     * configured character class permits {@code !}.
     */
    @Test
    void sharedConfigAllowedCharsEnforced() {
        doReturn(null).when(request).getHeader(AUTHORIZATION_HEADER);
        stubForwardedHeader(FORWARDED_USER_HEADER, "al!ce");
        assertFalse(filter.isLoginAttempt(request, response));

        setParser(MAX_USER_LENGTH_DEFAULT, "[a-z!]");
        assertTrue(filter.isLoginAttempt(request, response));
    }

    /**
     * The parser validates the entire {@code X-Forwarded-Groups} value against the configured header pattern:
     * a malformed value (here a trailing comma) rejects the whole header, authenticating the user with no
     * roles — stricter than lenient splitting.
     */
    @Test
    void malformedGroupsHeaderYieldsNoRoles() {
        doReturn(null).when(request).getHeader(AUTHORIZATION_HEADER);
        stubForwardedHeader(FORWARDED_USER_HEADER, "alice");
        stubForwardedHeader(FORWARDED_GROUPS_HEADER, "role:global-admin, user,");

        assertEquals(new Oauth2ProxyHeaderToken(Set.of(), "alice"), filter.createToken(request, response));
    }

    /**
     * Without a published parser (shared configuration service unavailable) the filter fails closed:
     * forwarded headers are not a login attempt and the request is challenged with 401, while Basic
     * authentication keeps working.
     */
    @Test
    void failsClosedWithoutParser() throws Exception {
        TestFilter.parserSupplier = () -> null;
        doReturn(null).when(request).getHeader(AUTHORIZATION_HEADER);

        assertFalse(filter.isLoginAttempt(request, response));
        assertFalse(filter.onAccessDenied(request, response));
        verify(response).setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        verify(response).setHeader(startsWith("WWW-Authenticate"), startsWith("BASIC"));

        doReturn(BASIC_ADMIN_ADMIN).when(request).getHeader(AUTHORIZATION_HEADER);
        assertTrue(filter.isLoginAttempt(request, response));
        assertInstanceOf(UsernamePasswordToken.class, filter.createToken(request, response));
    }

    /**
     * If the parser disappears between {@code isLoginAttempt} and {@code createToken} (configuration service
     * restart), an empty {@link Oauth2ProxyHeaderToken} is produced — rejected downstream by
     * {@code Oauth2ProxyHeaderRealm}.
     */
    @Test
    void createTokenWithoutParserYieldsEmptyToken() {
        TestFilter.parserSupplier = () -> null;
        doReturn(null).when(request).getHeader(AUTHORIZATION_HEADER);

        assertEquals(new Oauth2ProxyHeaderToken(Set.of(), null), filter.createToken(request, response));
    }

    private static void setParser(final int maxUserLength, final String allowedChars) {
        final var parser = new Oauth2ProxyHeaderParserImpl(new TestConfig(maxUserLength, allowedChars));
        TestFilter.parserSupplier = () -> parser;
    }

    /**
     * Stubs a forwarded header with a fresh {@link java.util.Enumeration} per invocation — the parser may
     * consume the header more than once per request, and an enumeration is exhausted after one pass.
     */
    private void stubForwardedHeader(final String header, final String... values) {
        doAnswer(invocation -> Collections.enumeration(List.of(values))).when(request).getHeaders(header);
    }
}
