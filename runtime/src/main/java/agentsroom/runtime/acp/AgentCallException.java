package agentsroom.runtime.acp;

import java.io.Serial;

/**
 * A call on an {@link AgentConnection} failed. The kind is what the session manager maps to an
 * {@code ErrorCode} for the stage the call belongs to (ADR 0003 detection table, ADR 0005).
 */
public final class AgentCallException extends Exception {

    @Serial
    private static final long serialVersionUID = 1L;

    /** How the call failed. */
    public enum Kind {
        /** The adapter executable (or the node running it) does not exist. */
        EXECUTABLE_MISSING,
        /** The process could not be started for another reason. */
        START_FAILED,
        /** No answer within the call's timeout. */
        TIMEOUT,
        /** The adapter answered with a JSON-RPC error; {@link #code()} is its code. */
        ERROR_RESPONSE,
        /** The connection is gone: the process exited or the runtime closed it. */
        CLOSED,
        /** Anything else. */
        FAILED
    }

    /** ACP {@code AUTHENTICATION_REQUIRED}. */
    public static final int AUTHENTICATION_REQUIRED = -32000;

    private final Kind kind;
    private final Integer code;

    public AgentCallException(Kind kind, String message) {
        this(kind, null, message, null);
    }

    public AgentCallException(Kind kind, Integer code, String message, Throwable cause) {
        super(message, cause);
        this.kind = kind;
        this.code = code;
    }

    public Kind kind() {
        return kind;
    }

    /** The JSON-RPC error code for {@link Kind#ERROR_RESPONSE}, else {@code null}. */
    public Integer code() {
        return code;
    }

    /** An error answer {@code -32000}: the adapter wants authentication. */
    public boolean isAuthenticationRequired() {
        return kind == Kind.ERROR_RESPONSE && code != null && code == AUTHENTICATION_REQUIRED;
    }
}
