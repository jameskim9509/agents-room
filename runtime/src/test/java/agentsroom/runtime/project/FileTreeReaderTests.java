package agentsroom.runtime.project;

import static agentsroom.runtime.project.FileSystemFixtures.file;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

class FileTreeReaderTests {

    @TempDir
    Path project;

    @Test
    void listsDirectoriesFirstThenFilesByNameInTreeOrder() throws IOException {
        file(project.resolve("b.txt"));
        file(project.resolve("A.txt"));
        file(project.resolve("src/main.ts"));
        file(project.resolve("src/lib/util.ts"));
        file(project.resolve("docs/guide.md"));
        Files.createDirectories(project.resolve("Empty"));

        FileTree tree = new FileTreeReader().read(project);

        assertThat(tree.truncated()).isFalse();
        assertThat(tree.entries()).containsExactly(
                new FileEntry("docs", true),
                new FileEntry("docs/guide.md", false),
                new FileEntry("Empty", true),
                new FileEntry("src", true),
                new FileEntry("src/lib", true),
                new FileEntry("src/main.ts", false),
                new FileEntry("A.txt", false),
                new FileEntry("b.txt", false));
    }

    @Test
    void stopsAtDepthTwoWithoutMarkingTheTreeTruncated() throws IOException {
        file(project.resolve("a/b/c/deep.txt"));

        FileTree tree = new FileTreeReader().read(project);

        assertThat(paths(tree)).containsExactly("a", "a/b");
        assertThat(tree.truncated()).isFalse();
    }

    @Test
    void listsGitAndNodeModulesButDoesNotDescend() throws IOException {
        file(project.resolve(".git/HEAD"));
        file(project.resolve("node_modules/left-pad/index.js"));
        file(project.resolve("package.json"));

        FileTree tree = new FileTreeReader().read(project);

        assertThat(tree.entries()).containsExactly(
                new FileEntry(".git", true), new FileEntry("node_modules", true), new FileEntry("package.json", false));
    }

    @Test
    void capsTheTreeAtFiveHundredEntries() throws IOException {
        for (int i = 0; i < FileTreeReader.MAX_ENTRIES + 1; i++) {
            file(project.resolve("file-%03d.txt".formatted(i)));
        }

        FileTree tree = new FileTreeReader().read(project);

        assertThat(tree.entries()).hasSize(FileTreeReader.MAX_ENTRIES);
        assertThat(tree.truncated()).isTrue();
        assertThat(tree.entries().getFirst().relativePath()).isEqualTo("file-000.txt");
    }

    @Test
    void exactlyFiveHundredEntriesIsNotTruncated() throws IOException {
        for (int i = 0; i < FileTreeReader.MAX_ENTRIES; i++) {
            file(project.resolve("file-%03d.txt".formatted(i)));
        }

        FileTree tree = new FileTreeReader().read(project);

        assertThat(tree.entries()).hasSize(FileTreeReader.MAX_ENTRIES);
        assertThat(tree.truncated()).isFalse();
    }

    @Test
    void keepsTheWholeFirstLevelBeforeAnySecondLevelEntry() throws IOException {
        file(project.resolve("a/a1.txt"));
        file(project.resolve("a/a2.txt"));
        file(project.resolve("b/b1.txt"));
        file(project.resolve("top.txt"));

        FileTree tree = new FileTreeReader(2, 4).read(project);

        assertThat(paths(tree)).containsExactly("a", "a/a1.txt", "b", "top.txt");
        assertThat(tree.truncated()).isTrue();
    }

    @Test
    void aCutDirectoryShowsSomeOfItsOwnEntriesInTreeOrder() throws IOException {
        for (int i = 0; i < 40; i++) {
            file(project.resolve("file-%02d.txt".formatted(i)));
            Files.createDirectories(project.resolve("dir-%02d".formatted(i)));
        }

        FileTree tree = new FileTreeReader(2, 5).read(project);

        // Only the first entries the OS lists are read, so which five is up to the OS.
        assertThat(tree.truncated()).isTrue();
        assertThat(tree.entries()).hasSize(5)
                .allSatisfy(entry -> assertThat(project.resolve(entry.relativePath())).exists())
                .isSortedAccordingTo(Comparator.comparing((FileEntry entry) -> !entry.directory())
                        .thenComparing(FileEntry::relativePath, String.CASE_INSENSITIVE_ORDER));
    }

    @Test
    void doesNotFollowSymlinks() throws IOException {
        file(project.resolve("real/inside.txt"));
        FileSystemFixtures.symlinkOrSkip(project.resolve("link"), project.resolve("real"));

        FileTree tree = new FileTreeReader().read(project);

        assertThat(tree.entries()).containsExactly(
                new FileEntry("real", true), new FileEntry("real/inside.txt", false), new FileEntry("link", false));
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void doesNotFollowJunctions() throws Exception {
        file(project.resolve("real/inside.txt"));
        FileSystemFixtures.junction(project.resolve("junction"), project.resolve("real"));

        FileTree tree = new FileTreeReader().read(project);

        assertThat(tree.entries()).containsExactly(
                new FileEntry("real", true), new FileEntry("real/inside.txt", false), new FileEntry("junction", false));
    }

    @Test
    void anUnreadableSubdirectoryIsListedWithoutChildren() throws IOException {
        file(project.resolve("locked/secret.txt"));
        file(project.resolve("open/visible.txt"));
        Runnable undo = FileSystemFixtures.denyListing(project.resolve("locked"));
        try {
            FileTree tree = new FileTreeReader().read(project);

            assertThat(paths(tree)).containsExactly("locked", "open", "open/visible.txt");
        } finally {
            undo.run();
        }
    }

    private static List<String> paths(FileTree tree) {
        return tree.entries().stream().map(FileEntry::relativePath).toList();
    }
}
