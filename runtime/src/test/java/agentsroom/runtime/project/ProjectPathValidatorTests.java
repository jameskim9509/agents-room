package agentsroom.runtime.project;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

class ProjectPathValidatorTests {

    private final ProjectPathValidator validator = new ProjectPathValidator();

    @TempDir
    Path temp;

    @Test
    void acceptsAReadableDirectoryAndReturnsItsRealPath() throws Exception {
        Path project = Files.createDirectory(temp.resolve("project"));

        assertThat(validator.validate(project.toString())).isEqualTo(project.toRealPath());
    }

    @Test
    void resolvesDotSegmentsToTheRealPath() throws Exception {
        Path project = Files.createDirectories(temp.resolve("project"));
        Files.createDirectory(temp.resolve("other"));

        assertThat(validator.validate(temp.resolve("other").resolve("..").resolve("project").toString()))
                .isEqualTo(project.toRealPath());
    }

    @Test
    void refusesRelativeAndEmptyPaths() {
        assertCode("relative/project", ProjectErrorCode.PATH_NOT_ABSOLUTE);
        assertCode("", ProjectErrorCode.PATH_NOT_ABSOLUTE);
        assertCode(null, ProjectErrorCode.PATH_NOT_ABSOLUTE);
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void refusesDriveRelativeWindowsPaths() {
        assertCode("C:project", ProjectErrorCode.PATH_NOT_ABSOLUTE);
        assertCode("\\project", ProjectErrorCode.PATH_NOT_ABSOLUTE);
    }

    @Test
    void refusesAMissingPath() {
        assertCode(temp.resolve("missing").toString(), ProjectErrorCode.PATH_NOT_FOUND);
        assertCode(temp.resolve("missing").resolve("deeper").toString(), ProjectErrorCode.PATH_NOT_FOUND);
    }

    @Test
    void refusesAStringThatIsNoPathOnThisSystem() {
        assertCode(temp + "/bad\u0000name", ProjectErrorCode.PATH_NOT_FOUND);
    }

    @Test
    void refusesAFile() throws IOException {
        Path file = Files.writeString(temp.resolve("README.md"), "hello", StandardCharsets.UTF_8);

        assertCode(file.toString(), ProjectErrorCode.PATH_NOT_DIRECTORY);
    }

    @Test
    void refusesADirectoryThatCannotBeListed() throws IOException {
        Path locked = Files.createDirectory(temp.resolve("locked"));
        Runnable undo = FileSystemFixtures.denyListing(locked);
        try {
            assertCode(locked.toString(), ProjectErrorCode.PATH_NOT_READABLE);
        } finally {
            undo.run();
        }
    }

    @Test
    void followsALinkToTheRealDirectory() throws Exception {
        Path target = Files.createDirectory(temp.resolve("target"));
        Path link = FileSystemFixtures.symlinkOrSkip(temp.resolve("link"), target);

        assertThat(validator.validate(link.toString())).isEqualTo(target.toRealPath());
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void resolvesWindowsShortNames() throws Exception {
        Path project = Files.createDirectory(temp.resolve("A Long Project Folder Name"));
        String shortPath = shortPathOf(project);
        assumeTrue(!shortPath.equalsIgnoreCase(project.toString()), "8.3 names are disabled on this volume");

        assertThat(validator.validate(shortPath)).isEqualTo(project.toRealPath());
        assertThat(validator.validate(shortPath).toString()).contains("A Long Project Folder Name");
    }

    private void assertCode(String path, ProjectErrorCode code) {
        assertThatThrownBy(() -> validator.validate(path))
                .isInstanceOfSatisfying(ProjectPathException.class, e -> assertThat(e.code()).isEqualTo(code));
    }

    /** {@code for %I in ("<path>") do @echo %~sI} prints the 8.3 form. */
    private static String shortPathOf(Path path) throws IOException, InterruptedException {
        Process cmd = new ProcessBuilder("cmd.exe", "/c", "for %I in (\"" + path + "\") do @echo %~sI")
                .redirectErrorStream(true)
                .start();
        String output = new String(cmd.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
        assumeTrue(cmd.waitFor(30, TimeUnit.SECONDS) && cmd.exitValue() == 0, "cmd failed: " + output);
        return output;
    }
}
