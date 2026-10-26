package agentsroom.runtime.session;

import agentsroom.contract.RejectReason;

/**
 * Answers the renderer that sent a command with a {@code commandRejected} frame (ADR 0005 decision 2).
 * The gateway builds it with the command's echo (type and ids); calls may come from any thread and
 * after the command handler returned. A command that is not rejected gets no direct answer: its effect
 * arrives as events.
 */
@FunctionalInterface
public interface CommandReply {

    /**
     * @param agentId the agent the command turned out to concern (a permission's agent), or {@code null}
     *     to echo only the ids the command carried
     */
    void reject(RejectReason reason, String message, String agentId);

    default void reject(RejectReason reason, String message) {
        reject(reason, message, null);
    }
}
