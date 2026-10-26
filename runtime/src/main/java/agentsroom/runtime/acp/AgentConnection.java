package agentsroom.runtime.acp;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * The port to one ACP adapter process (ADR 0003 decision 4): one connection, one process. The session
 * manager speaks only this interface, so another harness, or a thin JSON-RPC client in place of the SDK,
 * plugs in behind it without changes above. What the adapter sends on its own (updates, permission
 * requests, auth status, its exit) arrives through the {@link AgentConnectionListener} given at open.
 *
 * <p>The blocking calls run on the caller's thread and never on the connection's inbound thread.
 */
public interface AgentConnection {

    /** The adapter process, when the runtime could identify it. */
    Optional<ProcessHandle> process();

    /** {@code initialize} with protocol version 1 and the client capabilities of ADR 0003. */
    AgentHandshake initialize(Duration timeout) throws AgentCallException;

    /** {@code authenticate} with an agent-type method (never a terminal method). */
    void authenticate(String methodId, Duration timeout) throws AgentCallException;

    /** {@code session/new {cwd, mcpServers: []}} (ADR 0003 decision 6). */
    OpenedSession newSession(Path cwd, Duration timeout) throws AgentCallException;

    /**
     * {@code session/prompt} with one text block. No timeout: a run ends only with the adapter's answer
     * (ADR 0005 decision 5).
     *
     * @return completes with the ACP stop reason as received, or exceptionally with an
     *     {@link AgentCallException}
     */
    CompletableFuture<String> prompt(String sessionId, String text);

    /** {@code session/cancel}: a notification; the prompt still answers (with {@code cancelled}). */
    void cancel(String sessionId);

    /**
     * Ends the process gracefully: closes its stdin and gives it a moment to exit, then kills the whole
     * process tree, including descendants it had started (D13). Blocks at most about {@code timeout}.
     * No exit is reported to the listener after this.
     */
    void close(Duration timeout);

    /**
     * Kills the process tree at once, also while a {@link #close} is still under way (runtime
     * shutdown). No exit is reported to the listener after this.
     */
    void kill();
}
