package agentsroom.runtime.gateway;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.reactive.socket.WebSocketHandler;
import org.springframework.web.reactive.socket.WebSocketMessage;
import org.springframework.web.reactive.socket.WebSocketSession;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * The Renderer gateway (architecture.md): the only path between renderers and the runtime. It keeps
 * one {@link RendererConnection} per WebSocket session, hands inbound commands to the
 * {@link FrameHandler} and can broadcast a frame to every connected renderer.
 *
 * <p>Snapshot first, then events (D4). A renderer joins the broadcast set in the same step that
 * queues its snapshot, and every state change goes out through {@link #publish}, under the same
 * lock. So each renderer's snapshot either already contains a change, and then it gets none of the
 * change's frames, or it does not, and then it gets all of them, after the snapshot.
 *
 * <p>Connections reach this handler only through {@link RendererConnectionGate} (token and Origin).
 */
public final class RendererGateway implements WebSocketHandler, DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(RendererGateway.class);

    private final RendererMessageCodec codec;
    private final ObjectProvider<FrameHandler> frameHandler;
    private final Map<String, RendererConnection> connections = new ConcurrentHashMap<>();

    /** Orders snapshots against state changes. Held only for in-memory work and small file writes. */
    private final Object publishLock = new Object();

    /**
     * @param frameHandler looked up lazily: command handling depends on the gateway for broadcasts
     */
    public RendererGateway(RendererMessageCodec codec, ObjectProvider<FrameHandler> frameHandler) {
        this.codec = codec;
        this.frameHandler = frameHandler;
    }

    @Override
    public Mono<Void> handle(WebSocketSession session) {
        RendererConnection connection = new RendererConnection(session.getId(), codec);

        Mono<Void> outbound = session.send(connection.outbound().map(session::textMessage));
        // Empty = "connected": the connect step runs on the same ordered worker as the commands, so
        // it comes before any of them and never on a Netty event loop. The payload is copied to a
        // String before the thread hop because the frame's buffer is released after this operator.
        Flux<Optional<String>> events = session.receive()
                .filter(message -> message.getType() == WebSocketMessage.Type.TEXT)
                .map(message -> Optional.of(message.getPayloadAsText()))
                .startWith(Optional.empty());
        Mono<Void> inbound = events.publishOn(Schedulers.boundedElastic())
                .doOnNext(event -> dispatch(connection, event))
                .then();

        return Mono.zip(inbound, outbound).then().doFinally(signal -> {
            // Close first: a connect step still running then sees it and leaves the connection out.
            connection.complete();
            if (connections.remove(connection.id()) != null) {
                log.info("Renderer disconnected ({} open)", connections.size());
            }
        });
    }

    /**
     * Applies a state change that renderers can see and broadcasts the frames describing it, as one
     * step with respect to connecting renderers (see the class comment). Every change to state a
     * snapshot shows goes through here.
     *
     * @param change mutates the state and returns the frames to broadcast, in order. It runs under
     *     the publish lock, so it must stay short: no slow disk or network work.
     */
    public void publish(Supplier<List<ServerFrame>> change) {
        synchronized (publishLock) {
            change.get().forEach(this::broadcast);
        }
    }

    /** Sends {@code frame} to every connected renderer. State changes go through {@link #publish}. */
    public void broadcast(ServerFrame frame) {
        String text = codec.encode(frame);
        synchronized (publishLock) {
            connections.values().forEach(connection -> connection.sendText(text));
        }
    }

    /** How many renderers are connected. */
    public int connectionCount() {
        return connections.size();
    }

    /** Closes every renderer connection on shutdown. */
    @Override
    public void destroy() {
        connections.values().forEach(RendererConnection::complete);
    }

    private void dispatch(RendererConnection connection, Optional<String> event) {
        FrameHandler handler = frameHandler.getObject();
        try {
            if (event.isEmpty()) {
                connect(connection, handler);
                return;
            }
            ClientFrame frame;
            try {
                frame = codec.decode(event.get());
            } catch (MalformedFrameException e) {
                handler.onMalformedFrame(connection, e);
                return;
            }
            handler.onFrame(connection, frame);
        } catch (RuntimeException e) {
            // Keep the connection: one failed command must not cut the renderer off.
            log.error("Renderer frame handling failed", e);
        }
    }

    /** Queues the snapshot and joins the broadcast set in one step (D4). */
    private void connect(RendererConnection connection, FrameHandler handler) {
        synchronized (publishLock) {
            try {
                handler.onConnect(connection);
            } catch (RuntimeException e) {
                // Without its snapshot the renderer would show stale state; closing makes it reconnect.
                log.error("Could not send the snapshot; closing the renderer connection", e);
                connection.complete();
                return;
            }
            connections.put(connection.id(), connection);
            if (connection.isClosed()) {
                // The session ended while connecting; its finally step may already have run.
                connections.remove(connection.id());
                return;
            }
        }
        log.info("Renderer connected ({} open)", connections.size());
    }
}
