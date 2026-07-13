/*
 * Copyright (c) 2026 PANTHEON.tech, s.r.o. and others.  All rights reserved.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/epl-v10.html
 */
package tech.pantheon.testfilter.config;

import static java.util.Objects.requireNonNull;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import org.eclipse.jdt.annotation.NonNullByDefault;

/**
 * Static holder bridging the OSGi-managed {@link TestFilterConfig} to the Shiro-instantiated filter/realm classes.
 *
 * <p>Shiro creates filters and realms by reflection with no-arg constructors, so they cannot receive OSGi
 * injection. {@link TestFilterConfigImpl} publishes the current configuration here and consumers read it at
 * request time, making component start ordering irrelevant and configuration updates take effect immediately.
 *
 * <p>Until a configuration is published the holder fails closed: {@link TestFilterConfig.Mode#REJECT_ALL}.
 */
@NonNullByDefault
public final class TestFilterConfigHolder {
    private static final TestFilterConfig FAIL_CLOSED = () -> TestFilterConfig.Mode.REJECT_ALL;

    @SuppressFBWarnings(value = "NP_NONNULL_FIELD_NOT_INITIALIZED_IN_CONSTRUCTOR",
        justification = "Initialized at declaration, false positive on volatile static field")
    private static volatile TestFilterConfig config = FAIL_CLOSED;

    private TestFilterConfigHolder() {
        // Hidden on purpose
    }

    /**
     * Returns the current configuration.
     */
    public static TestFilterConfig get() {
        return config;
    }

    /**
     * Publishes a new configuration.
     *
     * @param newConfig the configuration to publish
     */
    public static void set(final TestFilterConfig newConfig) {
        config = requireNonNull(newConfig);
    }

    /**
     * Resets the holder to the fail-closed default.
     */
    public static void reset() {
        config = FAIL_CLOSED;
    }
}
