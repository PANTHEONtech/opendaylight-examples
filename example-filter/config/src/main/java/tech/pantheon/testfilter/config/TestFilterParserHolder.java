/*
 * Copyright (c) 2026 PANTHEON.tech, s.r.o. and others.  All rights reserved.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/epl-v10.html
 */
package tech.pantheon.testfilter.config;

import static java.util.Objects.requireNonNull;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.opendaylight.aaa.shiro.filters.Oauth2ProxyHeaderParser;

/**
 * Static holder bridging the DS-managed {@link Oauth2ProxyHeaderParser} service to the Shiro-instantiated
 * filter class.
 *
 * <p>Shiro creates filters and realms by reflection with no-arg constructors, so they cannot receive OSGi
 * injection. {@link TestFilterParserBridge} binds the service once via constructor {@code @Reference} and
 * publishes it here for consumption by {@code TestFilter} at request time.
 *
 * <p>Until a parser is published the holder yields {@code null}, and consumers fail closed.
 */
@NonNullByDefault
public final class TestFilterParserHolder {
    private static volatile @Nullable Oauth2ProxyHeaderParser parser;

    private TestFilterParserHolder() {
        // Hidden on purpose
    }

    /**
     * Returns the currently published parser, or {@code null} if none is bound.
     */
    public static @Nullable Oauth2ProxyHeaderParser get() {
        return parser;
    }

    /**
     * Publishes a newly-bound parser.
     *
     * @param newParser the parser to publish
     */
    public static void set(final Oauth2ProxyHeaderParser newParser) {
        parser = requireNonNull(newParser);
    }

    /**
     * Resets the holder to its unbound default.
     */
    public static void reset() {
        parser = null;
    }
}
