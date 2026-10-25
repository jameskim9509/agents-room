package agentsroom.runtime.project;

/**
 * One row of the minimal file tree.
 *
 * @param relativePath relative to the project path, {@code /}-separated, no trailing slash
 * @param directory {@code true} for a real directory; links (symlinks, junctions) are never directories
 *     here, because they are not followed
 */
public record FileEntry(String relativePath, boolean directory) {
}
