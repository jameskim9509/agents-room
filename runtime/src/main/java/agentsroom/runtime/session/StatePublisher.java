package agentsroom.runtime.session;

import agentsroom.contract.ServerMessage;
import java.util.List;
import java.util.function.Supplier;

/**
 * Where state changes renderers can see go out: the Renderer gateway's publish step (architecture.md,
 * Renderer gateway). The change runs under the gateway's publish lock together with the broadcast of
 * the frames it returns, so a renderer's snapshot either already shows a change or gets all of its
 * frames after the snapshot.
 */
public interface StatePublisher {

    /**
     * @param change mutates state and returns the frames that describe the change, in order; it must
     *     stay short (in-memory work, no blocking I/O)
     */
    void publish(Supplier<List<ServerMessage>> change);
}
