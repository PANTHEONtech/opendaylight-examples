/*
 * Copyright (c) 2026 PANTHEON.tech, s.r.o. and others.  All rights reserved.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/epl-v10.html
 */
package tech.pantheon.testfilter.config;

import org.eclipse.jdt.annotation.NonNullByDefault;

/**
 * Configuration for the Test filter/realm. Populated from {@code etc/tech.pantheon.testfilter.cfg} via OSGi
 * Configuration Admin.
 */
@NonNullByDefault
public interface TestFilterConfig {
    /**
     * Authentication decision mode of {@code TestRealm}.
     */
    enum Mode {
        /**
         * Any authentication attempt carrying an {@code Authorization} header succeeds and the user is granted
         * a single role equal to the username.
         */
        ALLOW_ALL,
        /**
         * Every authentication attempt is rejected.
         */
        REJECT_ALL
    }

    /**
     * Default value of the {@code mode} property.
     */
    String MODE_DEFAULT = "allow-all";

    /**
     * Returns the authentication decision mode.
     */
    Mode mode();
}
