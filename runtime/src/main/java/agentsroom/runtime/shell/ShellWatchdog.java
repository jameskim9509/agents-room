package agentsroom.runtime.shell;

import java.io.IOException;
import java.io.InputStream;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Notices that the shell is gone: its end of our stdin closed (EOF), or the parent process exited
 * (ADR 0001 decision 5). Either one fires the callback, once. The shell keeps stdin open for the
 * runtime's whole life and never writes to it; anything it does write is discarded.
 */
public final class ShellWatchdog {

    static final String STDIN_CLOSED = "stdin closed";
    static final String PARENT_EXITED = "parent process exited";

    private static final int READ_BUFFER_BYTES = 256;

    private final InputStream stdin;
    private final Optional<CompletableFuture<?>> parentExit;
    private final Consumer<String> onShellLost;
    private final AtomicBoolean fired = new AtomicBoolean();

    /**
     * @param stdin the runtime's stdin
     * @param parentExit completes when the parent process exits; empty when the parent is unknown
     * @param onShellLost receives the reason, at most once
     */
    public ShellWatchdog(InputStream stdin, Optional<CompletableFuture<?>> parentExit, Consumer<String> onShellLost) {
        this.stdin = stdin;
        this.parentExit = parentExit;
        this.onShellLost = onShellLost;
    }

    /** Starts watching. The stdin reader is a daemon thread, so it never keeps the JVM alive. */
    public void start() {
        Thread.ofPlatform().daemon().name("agents-room-stdin-watch").start(this::drainStdin);
        parentExit.ifPresent(exit -> exit.thenRun(() -> fire(PARENT_EXITED)));
    }

    private void drainStdin() {
        byte[] buffer = new byte[READ_BUFFER_BYTES];
        try {
            while (stdin.read(buffer) != -1) {
                // Discard: the control path carries nothing from the shell to the runtime.
            }
            fire(STDIN_CLOSED);
        } catch (IOException e) {
            fire(STDIN_CLOSED + " (" + e.getClass().getSimpleName() + ")");
        }
    }

    private void fire(String reason) {
        if (fired.compareAndSet(false, true)) {
            onShellLost.accept(reason);
        }
    }
}
