package agentsroom.runtime.config;

import java.io.Serial;

/** A required shell-provided variable is missing or invalid. The runtime refuses to start. */
public final class RuntimeEnvironmentException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    public RuntimeEnvironmentException(String message) {
        super(message);
    }

    public RuntimeEnvironmentException(String message, Throwable cause) {
        super(message, cause);
    }
}
