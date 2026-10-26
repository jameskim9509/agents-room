package agentsroom.runtime.acp;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Remembers every process an adapter started, so its whole tree can be killed even after the adapter
 * itself is gone (D13). When a process exits, its children are re-parented and no longer show up as
 * descendants of the adapter; codex-acp's tree is node → node → codex.exe → git.exe, and a crash of
 * the top process leaves git.exe running (acp-facts §2.4). So the descendants are collected while the
 * adapter runs (once a second, and on demand), and a kill takes everything ever seen that still lives.
 * {@link ProcessHandle} identity includes the start time, so a reused PID is never killed by mistake.
 *
 * <p>A sign-in is the exception. codex-acp opens the ChatGPT login page from the adapter's own process
 * (on Windows through PowerShell's {@code Start}), so a browser that was not running yet starts as the
 * adapter's descendant. Once a sign-in began ({@link #spareBrowsers}), web browsers are neither recorded
 * nor killed: the user's browser, with all its windows, must outlive a stop or a failed sign-in.
 */
final class ProcessTreeWatch {

    /** Executable names (lower case, without {@code .exe}) of web browsers a login page may open in. */
    static final Set<String> WEB_BROWSERS = Set.of("chrome", "google-chrome", "google-chrome-stable", "chromium",
            "chromium-browser", "msedge", "microsoft-edge", "microsoft-edge-stable", "firefox", "brave", "opera",
            "vivaldi", "whale", "naver-whale-stable", "iexplore", "librewolf", "waterfox", "floorp", "zen",
            "yandex", "arc");

    private static final Duration INTERVAL = Duration.ofSeconds(1);
    private static final Duration KILL_WAIT = Duration.ofSeconds(5);

    private static final ScheduledExecutorService SCHEDULER = Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().daemon().name("agents-room-process-watch").factory());

    private final ProcessHandle root;
    private final Set<ProcessHandle> seen = new LinkedHashSet<>();
    private final ScheduledFuture<?> refresher;
    private volatile boolean spareBrowsers;

    ProcessTreeWatch(ProcessHandle root) {
        this.root = root;
        this.refresher = root == null ? null
                : SCHEDULER.scheduleWithFixedDelay(this::collect, 0, INTERVAL.toMillis(), TimeUnit.MILLISECONDS);
    }

    /** From now on, web browsers in the tree are left alone: a sign-in may have opened one. */
    void spareBrowsers() {
        spareBrowsers = true;
        synchronized (this) {
            seen.removeIf(ProcessTreeWatch::isWebBrowser);
        }
    }

    /** Takes the current descendants into the record. */
    synchronized void collect() {
        if (root == null) {
            return;
        }
        root.descendants().filter(this::killable).forEach(seen::add);
        seen.removeIf(handle -> !handle.isAlive());
    }

    /**
     * Kills the adapter and everything it started that still runs, then waits for them to exit.
     *
     * @return whether they all exited within the wait
     */
    boolean killAll() {
        if (refresher != null) {
            refresher.cancel(false);
        }
        List<ProcessHandle> targets = targets();
        targets.forEach(ProcessHandle::destroyForcibly);
        try {
            CompletableFuture<?>[] exits =
                    targets.stream().map(ProcessHandle::onExit).toArray(CompletableFuture<?>[]::new);
            CompletableFuture.allOf(exits).get(KILL_WAIT.toMillis(), TimeUnit.MILLISECONDS);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    /** What a kill now ends: the adapter, its current descendants and every one seen before. */
    synchronized List<ProcessHandle> targets() {
        List<ProcessHandle> targets = new ArrayList<>();
        if (root == null) {
            return targets;
        }
        root.descendants().filter(this::killable).forEach(seen::add);
        targets.add(root);
        seen.stream().filter(this::killable).forEach(targets::add);
        return targets;
    }

    private boolean killable(ProcessHandle handle) {
        return !spareBrowsers || !isWebBrowser(handle);
    }

    private static boolean isWebBrowser(ProcessHandle handle) {
        return handle.info().command().map(ProcessTreeWatch::namesWebBrowser).orElse(false);
    }

    /** Whether an executable path names a web browser ({@link #WEB_BROWSERS}). */
    static boolean namesWebBrowser(String command) {
        return WEB_BROWSERS.contains(AcpSdkConnection.baseName(command));
    }
}
