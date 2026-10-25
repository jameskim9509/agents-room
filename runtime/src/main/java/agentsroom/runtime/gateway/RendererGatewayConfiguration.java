package agentsroom.runtime.gateway;

import agentsroom.runtime.config.RuntimeEnvironment;
import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.HandlerMapping;
import org.springframework.web.reactive.config.WebFluxConfigurer;
import org.springframework.web.reactive.handler.SimpleUrlHandlerMapping;
import org.springframework.web.reactive.socket.server.WebSocketService;
import org.springframework.web.reactive.socket.server.support.HandshakeWebSocketService;
import org.springframework.web.reactive.socket.server.upgrade.ReactorNettyRequestUpgradeStrategy;
import reactor.netty.http.server.WebsocketServerSpec;

/** Wires the renderer WebSocket: {@code ws://127.0.0.1:<port>/ws?token=<token>}. */
@Configuration(proxyBeanMethods = false)
class RendererGatewayConfiguration implements WebFluxConfigurer {

    static final String PATH = "/ws";

    /** Before annotated controllers (there are none). */
    private static final int HANDLER_MAPPING_ORDER = -1;

    /** Reactor Netty's default is 64 KiB; a long prompt in one command must still fit. */
    static final int MAX_INBOUND_FRAME_BYTES = 1024 * 1024;

    @Bean
    RendererConnectionGate rendererConnectionGate(RuntimeEnvironment environment) {
        return new RendererConnectionGate(environment.token(), environment.allowedOrigins());
    }

    @Bean
    RendererMessageCodec rendererMessageCodec() {
        return new ContractMessageCodec();
    }

    @Bean
    RendererGateway rendererGateway(RendererMessageCodec codec, ObjectProvider<FrameHandler> frameHandler) {
        return new RendererGateway(codec, frameHandler);
    }

    @Bean
    HandlerMapping rendererHandlerMapping(RendererGateway gateway) {
        return new SimpleUrlHandlerMapping(Map.of(PATH, gateway), HANDLER_MAPPING_ORDER);
    }

    @Override
    public WebSocketService getWebSocketService() {
        return new HandshakeWebSocketService(new ReactorNettyRequestUpgradeStrategy(
                () -> WebsocketServerSpec.builder().maxFramePayloadLength(MAX_INBOUND_FRAME_BYTES)));
    }
}
