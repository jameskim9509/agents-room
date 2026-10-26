package agentsroom.runtime.gateway;

import agentsroom.contract.ServerMessage;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

/** One connected renderer. Frames sent to it go out in the order they were sent. */
public final class RendererConnection {

    private static final Logger log = LoggerFactory.getLogger(RendererConnection.class);

    private final String id;
    private final RendererMessageCodec codec;
    private final Sinks.Many<String> outbound = Sinks.many().unicast().onBackpressureBuffer();
    private final AtomicBoolean closed = new AtomicBoolean();

    RendererConnection(String id, RendererMessageCodec codec) {
        this.id = id;
        this.codec = codec;
    }

    /** The WebSocket session id. */
    public String id() {
        return id;
    }

    /** Queues {@code frame} for this renderer. Safe to call from any thread. */
    public void send(ServerMessage frame) {
        sendText(codec.encode(frame));
    }

    synchronized void sendText(String text) {
        Sinks.EmitResult result = outbound.tryEmitNext(text);
        if (result.isFailure() && result != Sinks.EmitResult.FAIL_TERMINATED
                && result != Sinks.EmitResult.FAIL_CANCELLED) {
            log.warn("Dropped a frame for renderer connection {}: {}", id, result);
        }
    }

    Flux<String> outbound() {
        return outbound.asFlux();
    }

    /** Ends the outbound stream, which closes the WebSocket. */
    synchronized void complete() {
        closed.set(true);
        outbound.tryEmitComplete();
    }

    /** Whether {@link #complete()} ran: the session is over or ending. */
    boolean isClosed() {
        return closed.get();
    }
}
