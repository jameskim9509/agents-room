package agentsroom.runtime.workspace;

import agentsroom.runtime.project.FileTree;
import agentsroom.runtime.project.ProjectErrorCode;
import java.nio.file.Path;

/** Step one of opening a project, {@link WorkspaceService#prepareOpen(String)}: the checked path. */
public sealed interface ProjectOpening {

    /**
     * The path passed validation; {@link WorkspaceService#open(Ready)} makes it the current project.
     *
     * @param realPath the validated real path
     * @param tree its file tree, read in step one
     */
    record Ready(Path realPath, FileTree tree) implements ProjectOpening {
    }

    /**
     * Validation failed and nothing changed; the user picks again.
     *
     * @param path the path as requested
     * @param message diagnostic detail, not UI copy
     */
    record Refused(String path, ProjectErrorCode code, String message) implements ProjectOpening {
    }
}
