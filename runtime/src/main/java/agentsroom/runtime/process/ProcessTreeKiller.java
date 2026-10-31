package agentsroom.runtime.process;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Kills a process together with everything it started.
 *
 * <p>The descendant list is taken first: once the root dies, its children are re-parented and no
 * longer show up as its descendants. Then the root goes (so it cannot start more), then the
 * snapshot. {@code destroyForcibly} is used throughout, because on Windows a "normal" destroy is
 * forcible anyway and never reaches grandchildren (toolchain §6 row 5).
 */
public final class ProcessTreeKiller {

    private ProcessTreeKiller() {
    }

    /**
     * Kills {@code process} and its descendants, then waits for them to exit. For the root it also waits
     * on the {@link Process} itself, so {@link Process#isAlive()} is {@code false} on return.
     *
     * @return {@code true} when every killed process exited within {@code timeout}
     */
    public static boolean killTree(Process process, Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        boolean treeExited = killTree(process.toHandle(), timeout);
        try {
            return process.waitFor(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS) && treeExited;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /**
     * Kills {@code root} and its descendants, then waits for them to exit. The current process is
     * never killed, only its descendants.
     *
     * @return {@code true} when every killed process exited within {@code timeout}
     */
    public static boolean killTree(ProcessHandle root, Duration timeout) {
        List<ProcessHandle> targets = new ArrayList<>();
        List<ProcessHandle> descendants = root.descendants().toList();
        if (!root.equals(ProcessHandle.current())) {
            targets.add(root);
        }
        targets.addAll(descendants);
        return destroyAndAwait(targets, timeout);
    }

    /**
     * Kills the descendants of {@code root} but not {@code root} itself: the runtime's cleanup when it
     * ends (an orderly shutdown, or the halt after the grace period). A web browser that a sign-in released
     * from the runtime's job (the user's, {@link ChildProcessContainment#isReleased}, {@link WebBrowsers}) is
     * spared; another process the sign-in released is killed like the rest.
     *
     * @return {@code true} when every killed process exited within {@code timeout}
     */
    public static boolean killDescendants(ProcessHandle root, Duration timeout) {
        List<ProcessHandle> descendants = root.descendants().toList();
        return destroyAndAwait(
                descendants.stream().filter(process -> !isTheUsersBrowser(process)).toList(),
                timeout);
    }

    private static boolean isTheUsersBrowser(ProcessHandle process) {
        return ChildProcessContainment.isReleased(process) && WebBrowsers.isWebBrowser(process);
    }

    private static boolean destroyAndAwait(List<ProcessHandle> targets, Duration timeout) {
        targets.forEach(ProcessHandle::destroyForcibly);
        // Wait on every target; onExit() completes at once for a process that already ended. On Windows
        // it completes when the exit code is set, which can be a few milliseconds before the process
        // object is gone (Process.isAlive() still true); the killed process can no longer run code.
        CompletableFuture<?>[] exits = targets.stream()
                .map(ProcessHandle::onExit)
                .toArray(CompletableFuture<?>[]::new);
        try {
            CompletableFuture.allOf(exits).get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            return true;
        } catch (TimeoutException | ExecutionException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
