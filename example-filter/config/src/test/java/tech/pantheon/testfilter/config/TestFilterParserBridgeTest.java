/*
 * Copyright (c) 2026 PANTHEON.tech, s.r.o. and others.  All rights reserved.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/epl-v10.html
 */
package tech.pantheon.testfilter.config;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.opendaylight.aaa.shiro.filters.Oauth2ProxyHeaderParser;

@ExtendWith(MockitoExtension.class)
class TestFilterParserBridgeTest {
    @Mock
    private Oauth2ProxyHeaderParser parser;

    @AfterEach
    void afterEach() {
        TestFilterParserHolder.reset();
    }

    /**
     * Constructor activation (DS binding the {@code @Reference}) publishes the parser to
     * {@link TestFilterParserHolder}, and deactivation resets the holder to its unbound ({@code null}) default.
     */
    @Test
    void activationPublishesToHolder() {
        final var bridge = new TestFilterParserBridge(parser);
        assertSame(parser, TestFilterParserHolder.get());

        bridge.deactivate();
        assertNull(TestFilterParserHolder.get());
    }
}
