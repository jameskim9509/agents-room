package agentsroom.runtime.project;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.FileSystemException;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/** File-system setups for the project tests: links, junctions, unreadable directories. */
final class FileSystemFixtures {

    private FileSystemFixtures() {
    }

    static boolean isWindows() {
        return System.getProperty("os.name").toLowerCase(Locale.ROOT).startsWith("windows");
    }

    static Path file(Path path) throws IOException {
        Files.createDirectories(path.getParent());
        return Files.writeString(path, "x", StandardCharsets.UTF_8);
    }

    /** Creates a symlink, or skips the test when the OS does not allow it (Windows without developer mode). */
    static Path symlinkOrSkip(Path link, Path target) {
        try {
            return Files.createSymbolicLink(link, target);
        } catch (IOException | UnsupportedOperationException | SecurityException e) {
            assumeTrue(false, "symlinks are not allowed here: " + e);
            throw new AssertionError(e);
        }
    }

    /** Creates a Windows junction ({@code mklink /J}, no admin rights needed). Windows only. */
    static Path junction(Path link, Path target) throws IOException, InterruptedException {
        Process mklink = new ProcessBuilder("cmd.exe", "/c", "mklink", "/J", link.toString(), target.toString())
                .redirectErrorStream(true)
                .start();
        mklink.getInputStream().readAllBytes();
        assumeTrue(mklink.waitFor(30, TimeUnit.SECONDS) && mklink.exitValue() == 0, "mklink /J failed");
        return link;
    }

    /**
     * Makes {@code directory} unlistable for the current user and returns an undo action. Windows: a
     * DENY list-directory ACE; POSIX: mode 000. Skips the test when the change does not take effect
     * (for example when running as root).
     */
    static Runnable denyListing(Path directory) throws IOException {
        Runnable undo;
        if (isWindows()) {
            AclFileAttributeView view = Files.getFileAttributeView(directory, AclFileAttributeView.class);
            assumeTrue(view != null, "no ACL support");
            List<AclEntry> original = view.getAcl();
            UserPrincipal user = directory.getFileSystem()
                    .getUserPrincipalLookupService()
                    .lookupPrincipalByName(System.getProperty("user.name"));
            AclEntry deny = AclEntry.newBuilder()
                    .setType(AclEntryType.DENY)
                    .setPrincipal(user)
                    .setPermissions(AclEntryPermission.LIST_DIRECTORY)
                    .build();
            List<AclEntry> denied = new ArrayList<>(original);
            denied.addFirst(deny);
            view.setAcl(denied);
            undo = () -> {
                try {
                    view.setAcl(original);
                } catch (IOException e) {
                    throw new IllegalStateException(e);
                }
            };
        } else {
            PosixFileAttributeView view = Files.getFileAttributeView(directory, PosixFileAttributeView.class);
            assumeTrue(view != null, "no POSIX permission support");
            Set<PosixFilePermission> original = view.readAttributes().permissions();
            view.setPermissions(PosixFilePermissions.fromString("---------"));
            undo = () -> {
                try {
                    view.setPermissions(original);
                } catch (IOException e) {
                    throw new IllegalStateException(e);
                }
            };
        }
        if (isListable(directory)) {
            undo.run();
            assumeTrue(false, "denying list access had no effect for this user");
        }
        return undo;
    }

    private static boolean isListable(Path directory) {
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(directory)) {
            entries.iterator();
            return true;
        } catch (FileSystemException e) {
            return false;
        } catch (IOException e) {
            return false;
        }
    }
}
