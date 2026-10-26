package agentsroom.runtime.persistence;

import agentsroom.runtime.config.RuntimeEnvironment;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class PersistenceConfiguration {

    @Bean
    RuntimeDataDirectory runtimeDataDirectory(RuntimeEnvironment environment) {
        return RuntimeDataDirectory.at(environment.runtimeDirectory());
    }

    @Bean
    JsonFileStore jsonFileStore(Clock clock) {
        return new JsonFileStore(clock);
    }
}
