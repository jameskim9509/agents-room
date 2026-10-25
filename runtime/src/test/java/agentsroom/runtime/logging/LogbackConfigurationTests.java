package agentsroom.runtime.logging;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.joran.JoranConfigurator;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.LoggingEvent;
import ch.qos.logback.core.ConsoleAppender;
import ch.qos.logback.core.joran.spi.JoranException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Checks the real {@code logback-spring.xml}: stderr only, and everything passes the masking layer. */
class LogbackConfigurationTests {

    private final LoggerContext context = new LoggerContext();
    private ConsoleAppender<ILoggingEvent> appender;

    @BeforeEach
    void configure() throws JoranException {
        URL config = getClass().getClassLoader().getResource("logback-spring.xml");
        assertThat(config).isNotNull();
        JoranConfigurator configurator = new JoranConfigurator();
        configurator.setContext(context);
        configurator.doConfigure(config);
        appender = (ConsoleAppender<ILoggingEvent>) context.getLogger(Logger.ROOT_LOGGER_NAME).getAppender("STDERR");
    }

    @AfterEach
    void stop() {
        context.stop();
    }

    @Test
    void theOnlyAppenderWritesToStderr() {
        assertThat(appender).isNotNull();
        assertThat(appender.getTarget()).isEqualTo("System.err");
        assertThat(context.getLogger(Logger.ROOT_LOGGER_NAME).iteratorForAppenders())
                .toIterable()
                .containsExactly(appender);
    }

    @Test
    void masksTheMessageAndTheStackTraceAndPrintsTheTraceOnce() {
        Logger logger = context.getLogger("agentsroom.test");
        IllegalStateException failure = new IllegalStateException("failed with token=fake-token-value");
        Object[] arguments = {"fake-token-value"};
        LoggingEvent event =
                new LoggingEvent(Logger.class.getName(), logger, Level.WARN, "GET /ws?token={}", failure, arguments);

        String line = new String(appender.getEncoder().encode(event), StandardCharsets.UTF_8);

        assertThat(line).doesNotContain("fake-token-value");
        assertThat(line).contains("GET /ws?token=****", "failed with token=****");
        assertThat(line.split("IllegalStateException", -1)).hasSize(2);
    }
}
