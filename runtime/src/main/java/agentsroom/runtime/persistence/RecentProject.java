package agentsroom.runtime.persistence;

/**
 * A project the user opened before. The list doubles as the registry that keeps {@code projectId}
 * stable for a real path.
 *
 * @param projectId runtime-issued UUID
 * @param name the folder's base name
 * @param path the validated real path
 * @param lastOpenedAt epoch milliseconds
 */
public record RecentProject(String projectId, String name, String path, long lastOpenedAt) {
}
