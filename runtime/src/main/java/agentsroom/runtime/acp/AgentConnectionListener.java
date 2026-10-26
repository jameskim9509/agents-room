package agentsroom.runtime.acp;

import agentsroom.contract.AuthStatus;
import java.util.concurrent.CompletableFuture;

/**
 * What an adapter sends on its own. Calls come in the order the adapter sent the messages, on the
 * connection's inbound thread, which also gates the answers to pending calls: an implementation must
 * only hand the call off (enqueue) and return, never block.
 *
 * <p>{@code receivedAt} is when the runtime received the message, in epoch milliseconds (ADR 0005
 * decision 1).
 */
public interface AgentConnectionListener {

    /** A {@code session/update}. */
    void onUpdate(String sessionId, AgentUpdate update, long receivedAt);

    /**
     * A {@code session/request_permission}. Complete {@code answer} once, when the user answers or the
     * run is cancelled; until then the adapter waits.
     */
    void onPermissionRequest(PermissionAsk ask, long receivedAt, CompletableFuture<PermissionAnswer> answer);

    /** {@code _auth/status_update}: the kind and label only, identity fields already dropped (ADR 0004). */
    void onAuthStatus(AuthStatus status, long receivedAt);

    /** The adapter wrote a line on stdout that is not an ACP JSON-RPC message. */
    void onProtocolError(String detail, long receivedAt);

    /**
     * The process ended without the runtime asking (ADR 0005 transition table).
     *
     * @param exitCode {@code null} when unknown
     * @param detail masked diagnostic detail
     */
    void onExit(Integer exitCode, String detail, long receivedAt);
}
