package agentsroom.runtime.project;

import java.io.IOException;
import java.nio.file.DirectoryIteratorException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Reads the minimal file tree of a project (SCRUM-31, D17).
 *
 * <ul>
 *   <li>At most {@value #MAX_DEPTH} levels (the project's children and grandchildren) and at most
 *       {@value #MAX_ENTRIES} entries. When entries are left out the tree is marked truncated.
 *   <li>Shallower entries win: the whole first level is kept before any second-level entry.
 *   <li>In each directory: directories first, then files, both by name (case-insensitive).
 *   <li>The limit bounds the work too: a directory is read only as far as the entries still fit,
 *       plus one to tell that it was cut. Which entries of a cut directory are shown follows the
 *       order the OS lists them in; the shown ones are sorted as above.
 *   <li>Links are never followed: symlinks and Windows junctions are listed as non-directories.
 *   <li>{@code .git} and {@code node_modules} are listed but not descended into.
 *   <li>A subdirectory that cannot be read is listed without children.
 * </ul>
 */
@Component
public class FileTreeReader {

    public static final int MAX_DEPTH = 2;
    public static final int MAX_ENTRIES = 500;

    /** Listed, never descended into: big and not what the user wants to see first. */
    static final Set<String> NOT_DESCENDED = Set.of(".git", "node_modules");

    private static final Logger log = LoggerFactory.getLogger(FileTreeReader.class);

    private static final Comparator<Node> TREE_ORDER = Comparator.comparing((Node node) -> !node.directory())
            .thenComparing(Node::name, String.CASE_INSENSITIVE_ORDER)
            .thenComparing(Node::name);

    private final int maxDepth;
    private final int maxEntries;

    public FileTreeReader() {
        this(MAX_DEPTH, MAX_ENTRIES);
    }

    FileTreeReader(int maxDepth, int maxEntries) {
        this.maxDepth = maxDepth;
        this.maxEntries = maxEntries;
    }

    /** Reads the tree under {@code root}, a directory that already passed validation. */
    public FileTree read(Path root) {
        List<Node> topLevel = list(root, "", 1, maxEntries + 1);
        int budget = maxEntries;
        boolean truncated = false;
        if (topLevel.size() > budget) {
            topLevel = topLevel.subList(0, budget);
            truncated = true;
        }
        budget -= topLevel.size();

        // Breadth first, so a cap hit on a deep level never hides a shallower entry.
        Deque<Node> pending = new ArrayDeque<>(truncated ? List.of() : descendable(topLevel));
        while (!pending.isEmpty()) {
            Node directory = pending.removeFirst();
            // With no room left, one entry is enough to know that the tree is cut.
            List<Node> children = list(directory.path(), directory.relativePath() + "/", directory.depth() + 1,
                    budget + 1);
            if (children.isEmpty()) {
                continue;
            }
            if (budget == 0) {
                truncated = true;
                break;
            }
            if (children.size() > budget) {
                children = children.subList(0, budget);
                truncated = true;
            }
            directory.children().addAll(children);
            budget -= children.size();
            pending.addAll(descendable(children));
        }

        List<FileEntry> entries = new ArrayList<>();
        topLevel.forEach(node -> appendInTreeOrder(node, entries));
        return new FileTree(entries, truncated);
    }

    private List<Node> descendable(List<Node> nodes) {
        return nodes.stream().filter(node -> node.descend() && node.depth() < maxDepth).toList();
    }

    private static void appendInTreeOrder(Node node, List<FileEntry> entries) {
        entries.add(new FileEntry(node.relativePath(), node.directory()));
        node.children().forEach(child -> appendInTreeOrder(child, entries));
    }

    /** Reads at most {@code limit} entries of {@code directory}, sorted in tree order. */
    private static List<Node> list(Path directory, String parentPrefix, int depth, int limit) {
        List<Node> nodes = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory)) {
            for (Path entry : stream) {
                nodes.add(node(entry, parentPrefix, depth));
                if (nodes.size() == limit) {
                    break;
                }
            }
        } catch (IOException | DirectoryIteratorException | SecurityException e) {
            // Only the root passed validation; an unreadable subdirectory just shows no children.
            log.debug("Could not list a project directory at depth {}: {}", depth, e.getClass().getSimpleName());
        }
        nodes.sort(TREE_ORDER);
        return nodes;
    }

    private static Node node(Path entry, String parentPrefix, int depth) {
        String name = entry.getFileName().toString();
        boolean directory = false;
        try {
            BasicFileAttributes attributes =
                    Files.readAttributes(entry, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            // A Windows junction reads as a directory that is also "other" (a reparse point); a
            // symlink reads as a symbolic link. Neither is followed.
            boolean link = attributes.isSymbolicLink() || (attributes.isDirectory() && attributes.isOther());
            directory = attributes.isDirectory() && !link;
        } catch (IOException | SecurityException e) {
            log.debug("Could not read attributes of a project entry: {}", e.getClass().getSimpleName());
        }
        boolean descend = directory && !NOT_DESCENDED.contains(name.toLowerCase(Locale.ROOT));
        return new Node(entry, name, parentPrefix + name, directory, descend, depth, new ArrayList<>());
    }

    private record Node(
            Path path, String name, String relativePath, boolean directory, boolean descend, int depth,
            List<Node> children) {
    }
}
