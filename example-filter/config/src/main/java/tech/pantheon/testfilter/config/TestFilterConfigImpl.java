/*
 * Copyright (c) 2026 PANTHEON.tech, s.r.o. and others.  All rights reserved.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/epl-v10.html
 */
package tech.pantheon.testfilter.config;

import com.google.common.annotations.VisibleForTesting;
import java.util.Locale;
import org.eclipse.jdt.annotation.NonNullByDefault;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.metatype.annotations.AttributeDefinition;
import org.osgi.service.metatype.annotations.Designate;
import org.osgi.service.metatype.annotations.ObjectClassDefinition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Default implementation of {@link TestFilterConfig}.
 *
 * <p>Configuration is supplied via {@code etc/tech.pantheon.testfilter.cfg}:
 * <pre>{@code
 * mode = allow-all
 * }</pre>
 *
 * <p>On activation the parsed configuration is published to {@link TestFilterConfigHolder} for consumption by
 * the Shiro-instantiated filter/realm classes.
 */
@Component(service = TestFilterConfig.class, configurationPid = "tech.pantheon.testfilter", immediate = true)
@Designate(ocd = TestFilterConfigImpl.Configuration.class)
@NonNullByDefault
public final class TestFilterConfigImpl implements TestFilterConfig {
    @ObjectClassDefinition
    public @interface Configuration {
        @AttributeDefinition(description = """
            Authentication decision mode: allow-all grants access to any request carrying an Authorization \
            header, reject-all rejects every authentication attempt. An unrecognized value falls back to \
            reject-all.""")
        String mode() default MODE_DEFAULT;
    }

    private static final Logger LOG = LoggerFactory.getLogger(TestFilterConfigImpl.class);

    private final Mode mode;

    @Activate
    public TestFilterConfigImpl(final Configuration config) {
        this(config.mode());
    }

    private TestFilterConfigImpl(final String mode) {
        this.mode = parseMode(mode);
    }

    /**
     * Creates an instance and publishes it to {@link TestFilterConfigHolder} — the test-only equivalent of
     * SCR's constructor-then-{@link #activate()} sequence.
     */
    @VisibleForTesting
    static TestFilterConfigImpl activated(final String mode) {
        final var config = new TestFilterConfigImpl(mode);
        config.activate();
        return config;
    }

    /**
     * Registration is separated from construction on purpose: publishing {@code this} from the constructor
     * would let the reference escape before the instance is fully constructed, losing the final-field
     * visibility guarantee. SCR invokes this method only after constructor injection has completed.
     */
    @Activate
    void activate() {
        TestFilterConfigHolder.set(this);
        LOG.info("TestFilter configuration: mode={}", mode);
    }

    @Deactivate
    void deactivate() {
        TestFilterConfigHolder.reset();
    }

    @Override
    public Mode mode() {
        return mode;
    }

    private static Mode parseMode(final String value) {
        return switch (value.strip().toLowerCase(Locale.ENGLISH)) {
            case "allow-all" -> Mode.ALLOW_ALL;
            case "reject-all" -> Mode.REJECT_ALL;
            default -> {
                LOG.warn("Unrecognized mode '{}', failing closed to reject-all", value);
                yield Mode.REJECT_ALL;
            }
        };
    }
}
