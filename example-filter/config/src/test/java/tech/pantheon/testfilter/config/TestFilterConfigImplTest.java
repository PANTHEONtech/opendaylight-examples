/*
 * Copyright (c) 2026 PANTHEON.tech, s.r.o. and others.  All rights reserved.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/epl-v10.html
 */
package tech.pantheon.testfilter.config;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import tech.pantheon.testfilter.config.TestFilterConfig.Mode;

class TestFilterConfigImplTest {
    @AfterEach
    void afterEach() {
        TestFilterConfigHolder.reset();
    }

    /**
     * The {@code mode = allow-all} cfg value parses to {@link Mode#ALLOW_ALL}.
     */
    @Test
    void allowAllMode() {
        assertEquals(Mode.ALLOW_ALL, TestFilterConfigImpl.activated("allow-all").mode());
    }

    /**
     * The {@code mode = reject-all} cfg value parses to {@link Mode#REJECT_ALL}.
     */
    @Test
    void rejectAllMode() {
        assertEquals(Mode.REJECT_ALL, TestFilterConfigImpl.activated("reject-all").mode());
    }

    /**
     * Mode parsing tolerates surrounding whitespace and is case-insensitive, so hand-edited cfg files do not
     * need to match the exact spelling.
     */
    @Test
    void modeParsingIsLenient() {
        assertEquals(Mode.ALLOW_ALL, TestFilterConfigImpl.activated(" Allow-ALL ").mode());
    }

    /**
     * An unrecognized {@code mode} value fails closed to {@link Mode#REJECT_ALL} instead of silently
     * granting access.
     */
    @Test
    void unrecognizedModeFailsClosed() {
        assertEquals(Mode.REJECT_ALL, TestFilterConfigImpl.activated("garbage").mode());
    }

    /**
     * Component activation publishes the parsed configuration to {@link TestFilterConfigHolder}, and
     * deactivation resets the holder to its fail-closed ({@code reject-all}) default.
     */
    @Test
    void activationPublishesToHolder() {
        final var config = TestFilterConfigImpl.activated("allow-all");
        assertEquals(config, TestFilterConfigHolder.get());

        config.deactivate();
        assertEquals(Mode.REJECT_ALL, TestFilterConfigHolder.get().mode());
    }
}
