package agentsroom.runtime.gateway;

import java.io.Serial;

/**
 * An inbound text frame is not a valid agents-room command: not JSON, not an object, beyond the
 * runtime's JSON limits, with a repeated key, without a string {@code type}, of an unknown or legacy
 * type, or not shaped as {@code core/asyncapi.yaml} requires. The gateway answers it with
 * {@code commandRejected {reason: INVALID_MESSAGE}}.
 */
public final class MalformedFrameException extends Exception {

    @Serial
    private static final long serialVersionUID = 1L;

    private final transient CommandEcho echo;

    /**
     * @param message diagnostic detail; it may name a field, never quote a value
     * @param echo what the rejection echoes: the frame's {@code type} and the ids it carried
     */
    MalformedFrameException(String message, CommandEcho echo) {
        super(message);
        this.echo = echo;
    }

    CommandEcho echo() {
        return echo;
    }
}
