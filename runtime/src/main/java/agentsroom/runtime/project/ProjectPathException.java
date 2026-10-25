package agentsroom.runtime.project;

import java.io.Serial;

/** A picked path failed validation. The message is diagnostic detail, not UI copy. */
public final class ProjectPathException extends Exception {

    @Serial
    private static final long serialVersionUID = 1L;

    private final ProjectErrorCode code;

    public ProjectPathException(ProjectErrorCode code, String message) {
        super(message);
        this.code = code;
    }

    public ProjectErrorCode code() {
        return code;
    }
}
