package agentsroom.runtime.gateway;

import java.io.Serial;

/** An inbound text frame is not a command: not JSON, not an object, or without a string {@code type}. */
public final class MalformedFrameException extends Exception {

    @Serial
    private static final long serialVersionUID = 1L;

    public MalformedFrameException(String message) {
        super(message);
    }
}
