package agentsroom.runtime.logging;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.pattern.CompositeConverter;

/**
 * Logback {@code %mask(...)}: runs the wrapped pattern's output, stack traces included, through
 * {@link SecretMasker}. Registered in {@code logback-spring.xml}.
 */
public class MaskingConverter extends CompositeConverter<ILoggingEvent> {

    @Override
    protected String transform(ILoggingEvent event, String in) {
        return SecretMasker.mask(in);
    }
}
