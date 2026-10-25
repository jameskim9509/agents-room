package agentsroom.runtime.gateway;

/**
 * What the gateway calls for each renderer connection. Calls for one connection arrive in order and
 * never concurrently, on a worker thread (never a Netty event loop), so a handler may block briefly.
 */
public interface FrameHandler {

    /**
     * A renderer connected: queue its snapshot with {@link RendererConnection#send}. Called before
     * any of its frames, under the gateway's publish lock (see {@link RendererGateway}), so it must
     * only read in-memory state.
     */
    void onConnect(RendererConnection connection);

    /** A command arrived. State changes go out through {@link RendererGateway#publish}. */
    void onFrame(RendererConnection connection, ClientFrame frame);

    /** A text frame could not be decoded. */
    void onMalformedFrame(RendererConnection connection, MalformedFrameException error);
}
