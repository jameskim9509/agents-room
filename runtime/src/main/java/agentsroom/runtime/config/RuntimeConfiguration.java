package agentsroom.runtime.config;

import agentsroom.runtime.logging.SecretMasker;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Clock;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.boot.web.server.reactive.ConfigurableReactiveWebServerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.env.Environment;

/** Beans every part of the runtime shares. */
@Configuration(proxyBeanMethods = false)
public class RuntimeConfiguration {

    private static final byte[] LOOPBACK_IPV4 = {127, 0, 0, 1};
    private static final int EPHEMERAL_PORT = 0;

    /**
     * Read through Spring's {@link Environment}: in production that resolves the process environment,
     * and tests can supply the same names as properties.
     */
    @Bean
    RuntimeEnvironment runtimeEnvironment(Environment environment) {
        RuntimeEnvironment runtimeEnvironment = RuntimeEnvironment.from(environment::getProperty);
        SecretMasker.registerSecret(runtimeEnvironment.token());
        return runtimeEnvironment;
    }

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    /**
     * Always 127.0.0.1 on an OS-chosen port. Applied after Boot's own customizer, so a stray
     * {@code SERVER_ADDRESS} or {@code SERVER_PORT} in the user's environment cannot widen or pin the
     * listener (ADR 0001 decision 4).
     */
    @Bean
    WebServerFactoryCustomizer<ConfigurableReactiveWebServerFactory> loopbackServerCustomizer() {
        return new LoopbackServerCustomizer();
    }

    private static final class LoopbackServerCustomizer
            implements WebServerFactoryCustomizer<ConfigurableReactiveWebServerFactory>, Ordered {

        @Override
        public void customize(ConfigurableReactiveWebServerFactory factory) {
            try {
                factory.setAddress(InetAddress.getByAddress(LOOPBACK_IPV4));
            } catch (UnknownHostException e) {
                throw new IllegalStateException("127.0.0.1 is not a valid address", e);
            }
            factory.setPort(EPHEMERAL_PORT);
        }

        @Override
        public int getOrder() {
            return Ordered.LOWEST_PRECEDENCE;
        }
    }
}
