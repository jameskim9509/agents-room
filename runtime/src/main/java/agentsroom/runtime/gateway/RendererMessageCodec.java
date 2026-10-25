package agentsroom.runtime.gateway;

/**
 * Turns WebSocket text frames into commands and frames into text. The only place that knows the wire
 * format.
 *
 * <p>TODO(contract): the seam for the generated bindings. After the contract PR, implement this over
 * the generated {@code ClientMessage}/{@code ServerMessage} types (a Jackson 3 {@code JsonMapper}
 * with the discriminator) and delete {@link JsonMapRendererMessageCodec}.
 */
public interface RendererMessageCodec {

    /** @throws MalformedFrameException when {@code text} is not a command */
    ClientFrame decode(String text) throws MalformedFrameException;

    String encode(ServerFrame frame);
}
