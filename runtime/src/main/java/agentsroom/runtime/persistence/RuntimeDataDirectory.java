package agentsroom.runtime.persistence;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

/**
 * Where the runtime persists its state (D18): {@code <AGENTS_ROOM_DATA_DIR>/runtime/}. Nothing goes
 * into the user's project folder or {@code ~/.pixel-agents/} (architecture.md, Persistence).
 *
 * <pre>
 * runtime/
 *   recent-projects.json
 *   projects/&lt;projectId&gt;/agents.json
 * </pre>
 */
public final class RuntimeDataDirectory {

    static final String RECENT_PROJECTS_FILE = "recent-projects.json";
    static final String PROJECTS_DIRECTORY = "projects";
    static final String AGENTS_FILE = "agents.json";

    private final Path root;

    private RuntimeDataDirectory(Path root) {
        this.root = root;
    }

    /** Uses {@code root}, creating it when missing. */
    public static RuntimeDataDirectory at(Path root) {
        try {
            Files.createDirectories(root);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot create the runtime data directory " + root, e);
        }
        return new RuntimeDataDirectory(root);
    }

    public Path root() {
        return root;
    }

    public Path recentProjectsFile() {
        return root.resolve(RECENT_PROJECTS_FILE);
    }

    /**
     * @throws IllegalArgumentException when {@code projectId} is not a UUID, which keeps a hand-edited
     *     id from pointing outside {@code projects/}
     */
    public Path agentsFile(String projectId) {
        return root.resolve(PROJECTS_DIRECTORY).resolve(canonicalUuid(projectId)).resolve(AGENTS_FILE);
    }

    static String canonicalUuid(String value) {
        String canonical = UUID.fromString(value).toString();
        if (!canonical.equals(value)) {
            throw new IllegalArgumentException("Not a canonical UUID: " + value);
        }
        return canonical;
    }

    static boolean isCanonicalUuid(String value) {
        if (value == null) {
            return false;
        }
        try {
            canonicalUuid(value);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
