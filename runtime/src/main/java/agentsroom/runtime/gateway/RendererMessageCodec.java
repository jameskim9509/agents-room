package agentsroom.runtime.gateway;

import agentsroom.contract.ClientMessage;
import agentsroom.contract.ServerMessage;

/**
 * Turns WebSocket text frames into commands and frames into text. The only place that knows the wire
 * format: the {@code ClientMessage} and {@code ServerMessage} bindings generated from
 * {@code core/asyncapi.yaml} (ADR 0005 decision 11).
 */
public interface RendererMessageCodec {

    /**
     * @return the command, checked against its contract shape
     * @throws MalformedFrameException when {@code text} is not a valid agents-room command
     */
    ClientMessage decode(String text) throws MalformedFrameException;

    String encode(ServerMessage frame);
}
