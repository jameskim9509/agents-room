package agentsroom.runtime.project;

import java.util.List;

/**
 * The minimal, read-only file tree of a project (SCRUM-31).
 *
 * @param entries tree order: each directory is followed by its listed children
 * @param truncated {@code true} when entries within the depth limit were left out
 */
public record FileTree(List<FileEntry> entries, boolean truncated) {

    public FileTree {
        entries = List.copyOf(entries);
    }
}
