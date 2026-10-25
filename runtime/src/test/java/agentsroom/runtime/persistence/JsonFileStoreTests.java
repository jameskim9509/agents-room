package agentsroom.runtime.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.UserPrincipal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JsonFileStoreTests {

    private static final long NOW = 1_761_350_400_000L;

    private final JsonFileStore store = new JsonFileStore(Clock.fixed(Instant.ofEpochMilli(NOW), ZoneOffset.UTC));

    @TempDir
    Path dir;

    record Sample(int version, List<String> items) {
    }

    @Test
    void readsBackWhatItWrote() {
        Path file = dir.resolve("nested/sample.json");

        store.write(file, new Sample(1, List.of("a", "b")));

        assertThat(store.read(file, Sample.class, sample -> true)).contains(new Sample(1, List.of("a", "b")));
    }

    @Test
    void aMissingFileReadsAsEmpty() {
        assertThat(store.read(dir.resolve("absent.json"), Sample.class, sample -> true)).isEmpty();
    }

    @Test
    void replacesTheFileAtomicallyAndLeavesNoTempFiles() throws IOException {
        Path file = dir.resolve("sample.json");
        store.write(file, new Sample(1, List.of("old")));

        store.write(file, new Sample(1, List.of("new")));

        assertThat(store.read(file, Sample.class, sample -> true)).contains(new Sample(1, List.of("new")));
        assertThat(fileNames()).containsExactly("sample.json");
    }

    @Test
    void movesUnparseableFilesAsideAndKeepsTheirContent() throws IOException {
        Path file = Files.writeString(dir.resolve("sample.json"), "{not json", StandardCharsets.UTF_8);

        assertThat(store.read(file, Sample.class, sample -> true)).isEmpty();

        Path aside = dir.resolve("sample.json" + JsonFileStore.CORRUPT_SUFFIX + NOW);
        assertThat(file).doesNotExist();
        assertThat(aside).hasContent("{not json");
    }

    @Test
    void movesInvalidContentAsideWithoutOverwritingAnEarlierCopy() throws IOException {
        Path file = dir.resolve("sample.json");
        Path earlierAside = dir.resolve("sample.json" + JsonFileStore.CORRUPT_SUFFIX + NOW);
        Files.writeString(earlierAside, "earlier", StandardCharsets.UTF_8);
        store.write(file, new Sample(99, List.of()));

        assertThat(store.read(file, Sample.class, sample -> sample.version() == 1)).isEmpty();

        assertThat(earlierAside).hasContent("earlier");
        assertThat(dir.resolve(earlierAside.getFileName() + "-1")).exists();
        assertThat(file).doesNotExist();
    }

    @Test
    void aJsonNullIsCorrupt() throws IOException {
        Path file = Files.writeString(dir.resolve("sample.json"), "null", StandardCharsets.UTF_8);

        assertThat(store.read(file, Sample.class, sample -> true)).isEmpty();
        assertThat(file).doesNotExist();
    }

    @Test
    void aCorruptFileThatCannotBeMovedAsideFailsTheReadSoNobodyOverwritesIt() throws IOException {
        Path file = Files.writeString(dir.resolve("sample.json"), "{not json", StandardCharsets.UTF_8);
        Runnable undo = denyMoving(file);
        try {
            assertThatThrownBy(() -> store.read(file, Sample.class, sample -> true))
                    .isInstanceOf(UncheckedIOException.class)
                    .hasMessageContaining("could not be moved aside");
            assertThat(file).hasContent("{not json");
        } finally {
            undo.run();
        }
    }

    /**
     * Keeps {@code file} readable but not movable, and returns an undo action. Windows: DENY ACEs for
     * deleting the file and deleting children of its folder; POSIX: a read-only folder. Skips the test
     * when that has no effect (for example when running as root).
     */
    private static Runnable denyMoving(Path file) throws IOException {
        Path folder = file.getParent();
        List<Runnable> undo = new ArrayList<>();
        if (System.getProperty("os.name").toLowerCase(Locale.ROOT).startsWith("windows")) {
            undo.add(denyAcl(file, AclEntryPermission.DELETE));
            undo.add(denyAcl(folder, AclEntryPermission.DELETE_CHILD));
        } else {
            PosixFileAttributeView view = Files.getFileAttributeView(folder, PosixFileAttributeView.class);
            assumeTrue(view != null, "no POSIX permission support");
            Set<PosixFilePermission> original = view.readAttributes().permissions();
            view.setPermissions(PosixFilePermissions.fromString("r-x------"));
            undo.add(() -> setPermissions(view, original));
        }
        Runnable undoAll = () -> undo.reversed().forEach(Runnable::run);
        Path probe = file.resolveSibling(file.getFileName() + ".probe");
        try {
            Files.move(file, probe);
            Files.move(probe, file);
            undoAll.run();
            assumeTrue(false, "denying the move had no effect for this user");
        } catch (IOException expected) {
            // The file cannot be moved: the setup works.
        }
        return undoAll;
    }

    private static Runnable denyAcl(Path path, AclEntryPermission permission) throws IOException {
        AclFileAttributeView view = Files.getFileAttributeView(path, AclFileAttributeView.class);
        assumeTrue(view != null, "no ACL support");
        List<AclEntry> original = view.getAcl();
        UserPrincipal user = path.getFileSystem()
                .getUserPrincipalLookupService()
                .lookupPrincipalByName(System.getProperty("user.name"));
        List<AclEntry> denied = new ArrayList<>(original);
        denied.addFirst(AclEntry.newBuilder()
                .setType(AclEntryType.DENY)
                .setPrincipal(user)
                .setPermissions(permission)
                .build());
        view.setAcl(denied);
        return () -> {
            try {
                view.setAcl(original);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        };
    }

    private static void setPermissions(PosixFileAttributeView view, Set<PosixFilePermission> permissions) {
        try {
            view.setPermissions(permissions);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private List<String> fileNames() throws IOException {
        try (Stream<Path> files = Files.list(dir)) {
            return files.map(path -> path.getFileName().toString()).toList();
        }
    }
}
