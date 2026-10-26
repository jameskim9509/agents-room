package agentsroom.runtime.session;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

@Configuration(proxyBeanMethods = false)
class SessionConfiguration {

    @Bean
    AgentTimeouts agentTimeouts(Environment environment) {
        return AgentTimeouts.from(environment);
    }
}
