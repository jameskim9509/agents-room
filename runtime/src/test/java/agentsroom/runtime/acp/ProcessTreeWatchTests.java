package agentsroom.runtime.acp;

import static org.assertj.core.api.Assertions.assertThat;

import agentsroom.runtime.process.WebBrowsers;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The process-tree kill of D13, and its one exception: once a sign-in began, a web browser in the tree
 * (codex-acp opens the login page from its own process) is the user's and survives the kill. The
 * "browser" is a copy of a harmless system program named like one.
 */
class ProcessTreeWatchTests {

    private static final boolean WINDOWS =
            System.getProperty("os.name").toLowerCase(Locale.ROOT).startsWith("windows");
    private static final Duration WAIT = Duration.ofSeconds(10);

    @TempDir
    Path dir;

    private Process root;
    private ProcessHandle browser;

    @AfterEach
    void killWhatIsLeft() {
        if (browser != null) {
            browser.destroyForcibly();
        }
        if (root != null) {
            root.destroyForcibly();
        }
    }

    @Test
    void namesTheCommonWebBrowsersOnly() {
        assertThat(WebBrowsers.names("C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe"))
                .isTrue();
        assertThat(WebBrowsers.names("C:/Program Files (x86)/Microsoft/Edge/Application/MSEDGE.EXE"))
                .isTrue();
        assertThat(WebBrowsers.names("/usr/lib/firefox/firefox")).isTrue();
        assertThat(WebBrowsers.names("C:\\Program Files\\nodejs\\node.exe")).isFalse();
        assertThat(WebBrowsers.names("C:\\Users\\me\\codex.exe")).isFalse();
        assertThat(WebBrowsers.names("/usr/bin/git")).isFalse();
    }

    @Test
    void killsTheWholeTreeIncludingABrowserBeforeASignIn() throws Exception {
        ProcessTreeWatch watch = startTreeWithBrowser();

        assertThat(watch.killAll()).isTrue();

        assertThat(browser.onExit().get(WAIT.toMillis(), TimeUnit.MILLISECONDS).isAlive()).isFalse();
        assertThat(root.waitFor(WAIT.toMillis(), TimeUnit.MILLISECONDS)).isTrue();
    }

    @Test
    void sparesABrowserOnceASignInBegan() throws Exception {
        ProcessTreeWatch watch = startTreeWithBrowser();
        assertThat(watch.targets()).contains(root.toHandle(), browser);

        watch.spareBrowsers();

        assertThat(watch.targets()).as("the adapter's tree without the browser").contains(root.toHandle())
                .doesNotContain(browser);
        assertThat(watch.killAll()).isTrue();
        assertThat(root.waitFor(WAIT.toMillis(), TimeUnit.MILLISECONDS)).as("the adapter itself is killed").isTrue();
        if (!WINDOWS) {
            // On Windows the console stand-in dies with its console host (conhost.exe, which is killed);
            // a real browser has no console.
            assertThat(browser.isAlive()).as("the user's browser stays").isTrue();
        }
    }

    /** root (a shell) → "chrome" (a copy of ping or sleep, named like a browser), which runs a while. */
    private ProcessTreeWatch startTreeWithBrowser() throws Exception {
        Path program = WINDOWS ? Path.of(System.getenv("SystemRoot"), "System32", "PING.EXE") : Path.of("/bin/sleep");
        Assumptions.assumeTrue(Files.isRegularFile(program), program + " is needed as a stand-in browser");
        Path chrome = dir.resolve(WINDOWS ? "chrome.exe" : "chrome");
        Files.copy(program, chrome);
        if (!WINDOWS) {
            chrome.toFile().setExecutable(true);
        }
        List<String> command = WINDOWS
                ? List.of("cmd.exe", "/d", "/c", chrome.toString(), "-n", "60", "127.0.0.1")
                // "; true" keeps sh from exec'ing the last command: the browser stays its child.
                : List.of("/bin/sh", "-c", "'" + chrome + "' 60; true");
        root = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(dir.resolve("out.txt").toFile())
                .start();
        browser = awaitChild(root.toHandle(), chrome);
        ProcessTreeWatch watch = new ProcessTreeWatch(root.toHandle());
        watch.collect();
        return watch;
    }

    private static ProcessHandle awaitChild(ProcessHandle parent, Path executable) throws InterruptedException,
            IOException {
        String name = executable.getFileName().toString().toLowerCase(Locale.ROOT);
        long deadline = System.nanoTime() + WAIT.toNanos();
        while (System.nanoTime() < deadline) {
            Optional<ProcessHandle> child = parent.descendants()
                    .filter(handle -> handle.info().command()
                            .map(command -> Path.of(command).getFileName().toString().toLowerCase(Locale.ROOT)
                                    .equals(name))
                            .orElse(false))
                    .findFirst();
            if (child.isPresent()) {
                return child.get();
            }
            Thread.sleep(50);
        }
        throw new IOException("the stand-in browser did not start under " + parent.pid());
    }
}
