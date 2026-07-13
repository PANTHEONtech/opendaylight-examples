/*
 * Copyright (c) 2026 PANTHEON.tech, s.r.o. and others.  All rights reserved.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/epl-v10.html
 */
package tech.pantheon.testfilter;

import java.util.Set;
import org.apache.shiro.authc.AuthenticationException;
import org.apache.shiro.authc.AuthenticationInfo;
import org.apache.shiro.authc.AuthenticationToken;
import org.apache.shiro.authc.SimpleAuthenticationInfo;
import org.apache.shiro.authc.UsernamePasswordToken;
import org.apache.shiro.authc.credential.AllowAllCredentialsMatcher;
import org.apache.shiro.authz.AuthorizationInfo;
import org.apache.shiro.authz.SimpleAuthorizationInfo;
import org.apache.shiro.realm.AuthorizingRealm;
import org.apache.shiro.subject.PrincipalCollection;
import org.opendaylight.aaa.api.shiro.principal.ODLPrincipal;
import org.opendaylight.aaa.shiro.principal.ODLPrincipalImpl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tech.pantheon.testfilter.config.TestFilterConfigHolder;

/**
 * Realm demonstrating the AAA-307 pluggable realm mechanism. Consumes the
 * {@link UsernamePasswordToken}s created by {@link TestFilter} from Basic {@code Authorization} headers.
 *
 * <p>The intended integration makes an external call to the Test service to validate credentials and fetch the
 * user's roles. Until that service is available the decision is simulated by the {@code mode} property of
 * {@code etc/tech.pantheon.testfilter.cfg}: {@code allow-all} authenticates any user and grants a single
 * role equal to the username, {@code reject-all} rejects every attempt. The configuration is read at
 * authentication time, so updates take effect immediately.
 */
public final class TestRealm extends AuthorizingRealm {
    private static final Logger LOG = LoggerFactory.getLogger(TestRealm.class);

    public TestRealm() {
        setAuthenticationTokenClass(UsernamePasswordToken.class);
        setCredentialsMatcher(new AllowAllCredentialsMatcher());
    }

    @Override
    protected AuthenticationInfo doGetAuthenticationInfo(final AuthenticationToken token)
            throws AuthenticationException {
        if (!(token instanceof UsernamePasswordToken usernamePassword)) {
            throw new AuthenticationException("Only UsernamePasswordToken is supported by TestRealm");
        }
        final var username = usernamePassword.getUsername();
        if (username == null || username.isBlank()) {
            throw new AuthenticationException("UsernamePasswordToken is missing valid user to authenticate");
        }
        return switch (TestFilterConfigHolder.get().mode()) {
            case REJECT_ALL -> throw new AuthenticationException("TestRealm is in reject-all mode");
            case ALLOW_ALL -> {
                final var roles = fetchRolesFromTestService(username);
                LOG.debug("TestRealm authenticated user '{}' with roles {}", username, roles);
                final var odlPrincipal = ODLPrincipalImpl.createODLPrincipal(username, null, username, roles);
                yield new SimpleAuthenticationInfo(odlPrincipal, token.getCredentials(), getName());
            }
        };
    }

    /**
     * Returns the roles of the given user.
     *
     * <p>TODO: replace the body with an external call to the Test service that validates the credentials and
     * returns the user's roles. Currently simulated: the user is granted a single role equal to the username.
     */
    private static Set<String> fetchRolesFromTestService(final String username) {
        return Set.of(username);
    }

    @Override
    protected AuthorizationInfo doGetAuthorizationInfo(final PrincipalCollection principalCollection) {
        final var primary = principalCollection.getPrimaryPrincipal();
        if (primary instanceof ODLPrincipal odlPrincipal) {
            return new SimpleAuthorizationInfo(odlPrincipal.getRoles());
        }
        LOG.error("Unsupported principal {}", primary);
        return new SimpleAuthorizationInfo();
    }
}
