package agentsroom.runtime.project;

import java.util.List;

/**
 * An open project as the renderer sees it (SCRUM-31).
 *
 * @param projectId runtime-issued UUID, stable for the same real path across restarts
 * @param name the folder's base name
 * @param path the validated real path; it becomes the ACP {@code session/new} cwd
 * @param files the minimal file tree
 * @param filesTruncated whether the tree left entries out
 */
public record ProjectInfo(String projectId, String name, String path, List<FileEntry> files, boolean filesTruncated) {

    public ProjectInfo {
        files = List.copyOf(files);
    }
}
