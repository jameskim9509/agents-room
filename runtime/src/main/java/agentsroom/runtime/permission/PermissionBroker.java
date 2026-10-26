package agentsroom.runtime.permission;

import agentsroom.runtime.acp.PermissionAnswer;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * The Permission broker (architecture.md): the pending {@code session/request_permission} requests of
 * all agents, by {@code permissionId}. Each one holds the answer the adapter waits for, the JSON-RPC
 * request of the SDK, completed exactly once: with the option the user picked, or with
 * {@code cancelled} when the run is cancelled or the agent stops (ADR 0005 decision 7). When the adapter
 * withdraws a request ({@code $/cancel_request}), the SDK cancels the answer instead and the request is
 * forgotten. The runtime never answers {@code selected} on its own.
 */
@Component
public class PermissionBroker {

    /**
     * One pending request.
     *
     * @param agentId the agent that asked
     */
    public record Pending(String permissionId, String agentId, CompletableFuture<PermissionAnswer> answer) {
    }

    private final Map<String, Pending> pending = new ConcurrentHashMap<>();

    /** Registers a request and issues its {@code permissionId}. */
    public String register(String agentId, CompletableFuture<PermissionAnswer> answer) {
        String permissionId = UUID.randomUUID().toString();
        pending.put(permissionId, new Pending(permissionId, agentId, answer));
        return permissionId;
    }

    /** The pending request, or empty when none has this id (answered, cancelled or never issued). */
    public Optional<Pending> find(String permissionId) {
        return permissionId == null ? Optional.empty() : Optional.ofNullable(pending.get(permissionId));
    }

    /**
     * Answers the adapter and forgets the request.
     *
     * @return whether the adapter gets this answer: false when the request was not pending, or the
     *     adapter had withdrawn it ({@code $/cancel_request}, which cancels the answer)
     */
    public boolean answer(String permissionId, PermissionAnswer answer) {
        Pending removed = permissionId == null ? null : pending.remove(permissionId);
        if (removed == null) {
            return false;
        }
        return removed.answer().complete(answer);
    }

    /** Forgets a request the adapter withdrew, without answering it. */
    public void forget(String permissionId) {
        if (permissionId != null) {
            pending.remove(permissionId);
        }
    }
}
