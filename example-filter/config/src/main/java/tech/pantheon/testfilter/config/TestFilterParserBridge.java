/*
 * Copyright (c) 2026 PANTHEON.tech, s.r.o. and others.  All rights reserved.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/epl-v10.html
 */
package tech.pantheon.testfilter.config;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.opendaylight.aaa.shiro.filters.Oauth2ProxyHeaderParser;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Reference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * DS component binding aaa's {@link Oauth2ProxyHeaderParser} service the sanctioned way — constructor
 * {@code @Reference} — and publishing it to {@link TestFilterParserHolder} for the Shiro-instantiated
 * {@code TestFilter} to consume.
 *
 * <p>A static reference is the only policy constructor injection supports: if the parser service is
 * re-registered (e.g. its own configuration changes), this component is deactivated and reactivated to
 * rebind, resetting the holder in between.
 */
@Component(immediate = true)
@NonNullByDefault
public final class TestFilterParserBridge {
    private static final Logger LOG = LoggerFactory.getLogger(TestFilterParserBridge.class);

    @Activate
    public TestFilterParserBridge(@Reference final Oauth2ProxyHeaderParser parser) {
        TestFilterParserHolder.set(parser);
        LOG.info("Oauth2ProxyHeaderParser bound: forwarded-header authentication enabled");
    }

    @Deactivate
    void deactivate() {
        TestFilterParserHolder.reset();
    }
}
