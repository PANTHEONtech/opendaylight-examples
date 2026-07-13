/*
 * Copyright (c) 2026 PANTHEON.tech, s.r.o. and others.  All rights reserved.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/epl-v10.html
 */
package tech.pantheon.testfilter;

import com.google.common.annotations.VisibleForTesting;
import java.util.Set;
import java.util.function.Supplier;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import org.apache.shiro.authc.AuthenticationToken;
import org.apache.shiro.web.filter.authc.BasicHttpAuthenticationFilter;
import org.eclipse.jdt.annotation.Nullable;
import org.opendaylight.aaa.shiro.filters.Oauth2ProxyHeaderParser;
import org.opendaylight.aaa.shiro.filters.Oauth2ProxyHeaderToken;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tech.pantheon.testfilter.config.TestFilterParserHolder;

/**
 * Shiro filter demonstrating the AAA-307 pluggable filter mechanism.
 *
 * <p>Extends the stock {@code authcBasic} filter: a request carrying a Basic {@code Authorization} header is
 * turned into a {@link org.apache.shiro.authc.UsernamePasswordToken} and processed by {@link TestRealm}. As a
 * fallback, a request forwarded by an upstream OAuth2-Proxy instance — carrying {@code X-Forwarded-User} and
 * {@code X-Forwarded-Groups} headers — is turned into an {@link Oauth2ProxyHeaderToken} and processed by
 * {@code Oauth2ProxyHeaderRealm}. Requests carrying neither are challenged with 401.
 *
 * <p>The forwarded headers are parsed and validated by aaa's {@link Oauth2ProxyHeaderParser}, bound once by
 * {@code TestFilterParserBridge} — a DS component in the companion {@code config} bundle, constructor-injected
 * via {@code @Reference} — and published to {@link TestFilterParserHolder} for this filter to read at request
 * time. Shiro instantiates this filter by reflection, so it cannot itself receive DS/blueprint injection; the
 * holder is the only channel available to reach it. That service is built by SCR from the
 * {@code Oauth2ProxyHeaderParserConfig} service (the common
 * {@code etc/org.opendaylight.aaa.shiro.oauth2proxyheaderparser.cfg}), so the config-via-OSGi rule holds
 * transitively and a cfg edit rebinds the bridge with the new limits. While no parser is bound the filter
 * fails closed: forwarded-header authentication is disabled and only Basic authentication works.
 *
 * <p><strong>Security prerequisite:</strong> the fallback trusts the forwarded headers, so direct HTTP access
 * to ODL that bypasses the proxy must be blocked at the network level. Failure to do so allows any caller to
 * forge these headers and authenticate as an arbitrary user.
 */
public final class TestFilter extends BasicHttpAuthenticationFilter {
    private static final Logger LOG = LoggerFactory.getLogger(TestFilter.class);

    /**
     * The production parser source: reads the value {@code TestFilterParserBridge} publishes to
     * {@link TestFilterParserHolder}.
     */
    @VisibleForTesting
    static final Supplier<@Nullable Oauth2ProxyHeaderParser> OSGI_LOOKUP = TestFilterParserHolder::get;

    /**
     * Test seam: unit tests run outside OSGi (where {@link #OSGI_LOOKUP} yields {@code null}) and substitute
     * a parser built from a known configuration.
     */
    @VisibleForTesting
    static volatile Supplier<@Nullable Oauth2ProxyHeaderParser> parserSupplier = OSGI_LOOKUP;

    @Override
    protected boolean isLoginAttempt(final ServletRequest request, final ServletResponse response) {
        if (super.isLoginAttempt(request, response)) {
            return true;
        }
        final var parser = parserSupplier.get();
        if (parser == null) {
            LOG.debug("Oauth2ProxyHeaderParser service not available, forwarded-header authentication is disabled");
            return false;
        }
        return parser.parseUser(request) != null;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Inherited two-stage handling: a login attempt (Basic or forwarded headers) is executed against the
     * realms, anything else is challenged with 401 and {@code WWW-Authenticate: BASIC}. Overridden without
     * behavior change to be reachable from same-package tests.
     */
    @Override
    protected boolean onAccessDenied(final ServletRequest request, final ServletResponse response) throws Exception {
        return super.onAccessDenied(request, response);
    }

    @Override
    protected AuthenticationToken createToken(final ServletRequest request, final ServletResponse response) {
        if (super.isLoginAttempt(request, response)) {
            // Basic Authorization header takes precedence over forwarded headers
            LOG.info("Using Basic authentication");
            return super.createToken(request, response);
        }
        LOG.info("Using OAuth2-Proxy forwarded-header authentication");
        final var parser = parserSupplier.get();
        if (parser == null) {
            // The service can disappear between isLoginAttempt and createToken while the configuration
            // restarts; Oauth2ProxyHeaderRealm rejects the null user, yielding 401.
            return new Oauth2ProxyHeaderToken(Set.of(), null);
        }
        return parser.parseToken(request);
    }
}
