package agentsroom.runtime.project;

import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import org.springframework.stereotype.Component;

/**
 * Checks a folder path before it becomes a project (SCRUM-18, 31). The runtime trusts no path, not
 * even one the shell's folder picker returned (architecture.md, Project/Workspace service).
 *
 * <p>The checks run in this order (D17), and the first failure decides the code:
 *
 * <ol>
 *   <li>absolute → else {@link ProjectErrorCode#PATH_NOT_ABSOLUTE}
 *   <li>{@link Path#toRealPath} resolves links, letter case and Windows 8.3 short names → else
 *       {@link ProjectErrorCode#PATH_NOT_FOUND} ({@link ProjectErrorCode#PATH_NOT_READABLE} when access
 *       is denied)
 *   <li>a directory → else {@link ProjectErrorCode#PATH_NOT_DIRECTORY}
 *   <li>its entries can be listed → else {@link ProjectErrorCode#PATH_NOT_READABLE}
 * </ol>
 */
@Component
public class ProjectPathValidator {

    /**
     * @return the real path of the directory, which becomes the project path and the ACP {@code cwd}
     * @throws ProjectPathException when a check fails
     */
    public Path validate(String rawPath) throws ProjectPathException {
        Path path = parse(rawPath);
        if (!path.isAbsolute()) {
            throw new ProjectPathException(ProjectErrorCode.PATH_NOT_ABSOLUTE, "Path is not absolute");
        }
        Path realPath;
        try {
            realPath = path.toRealPath();
        } catch (AccessDeniedException e) {
            throw new ProjectPathException(
                    ProjectErrorCode.PATH_NOT_READABLE, "Access denied while resolving the path");
        } catch (IOException e) {
            throw new ProjectPathException(
                    ProjectErrorCode.PATH_NOT_FOUND, "Path does not exist (" + e.getClass().getSimpleName() + ")");
        }
        if (!Files.isDirectory(realPath)) {
            throw new ProjectPathException(ProjectErrorCode.PATH_NOT_DIRECTORY, "Path is not a directory");
        }
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(realPath)) {
            // Opening the stream is the readability test; on Windows it already lists the first entry.
            entries.iterator();
        } catch (IOException | SecurityException e) {
            throw new ProjectPathException(ProjectErrorCode.PATH_NOT_READABLE, "Directory cannot be listed ("
                    + e.getClass().getSimpleName() + ")");
        }
        return realPath;
    }

    private static Path parse(String rawPath) throws ProjectPathException {
        if (rawPath == null || rawPath.isBlank()) {
            throw new ProjectPathException(ProjectErrorCode.PATH_NOT_ABSOLUTE, "Path is empty");
        }
        try {
            return Path.of(rawPath);
        } catch (InvalidPathException e) {
            throw new ProjectPathException(ProjectErrorCode.PATH_NOT_FOUND, "Not a valid path on this system");
        }
    }
}
