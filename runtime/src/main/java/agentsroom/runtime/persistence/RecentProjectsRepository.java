package agentsroom.runtime.persistence;

import java.io.UncheckedIOException;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Repository;

/** {@code recent-projects.json}: the opened projects, most recent first. */
@Repository
public class RecentProjectsRepository {

    static final int FORMAT_VERSION = 1;

    private static final Logger log = LoggerFactory.getLogger(RecentProjectsRepository.class);

    private final JsonFileStore store;
    private final RuntimeDataDirectory dataDirectory;

    public RecentProjectsRepository(JsonFileStore store, RuntimeDataDirectory dataDirectory) {
        this.store = store;
        this.dataDirectory = dataDirectory;
    }

    /**
     * @return the stored list; empty when the file is missing or was corrupt (moved aside)
     * @throws UncheckedIOException when the file exists but cannot be read; then it must not be
     *     overwritten
     */
    public List<RecentProject> load() {
        return store.read(dataDirectory.recentProjectsFile(), RecentProjectsDocument.class,
                        RecentProjectsRepository::isValid)
                .map(RecentProjectsDocument::projects)
                .orElse(List.of());
    }

    /**
     * Saves the list. A failed write is logged, not thrown: the project still opens for this session.
     *
     * @return whether the list reached the disk
     */
    public boolean save(List<RecentProject> projects) {
        try {
            store.write(dataDirectory.recentProjectsFile(), new RecentProjectsDocument(FORMAT_VERSION, projects));
            return true;
        } catch (UncheckedIOException e) {
            log.error("Could not save the recent projects", e);
            return false;
        }
    }

    private static boolean isValid(RecentProjectsDocument document) {
        return document.version() == FORMAT_VERSION
                && document.projects() != null
                && document.projects().stream().allMatch(RecentProjectsRepository::isValid);
    }

    private static boolean isValid(RecentProject project) {
        return project != null
                && RuntimeDataDirectory.isCanonicalUuid(project.projectId())
                && project.name() != null
                && isAbsolutePath(project.path())
                && project.lastOpenedAt() >= 0;
    }

    private static boolean isAbsolutePath(String path) {
        if (path == null) {
            return false;
        }
        try {
            return Path.of(path).isAbsolute();
        } catch (InvalidPathException e) {
            return false;
        }
    }

    /** The file format. {@code version} lets a later runtime migrate it. */
    record RecentProjectsDocument(int version, List<RecentProject> projects) {
    }
}
