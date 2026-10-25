package agentsroom.runtime.process;

import java.io.IOException;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Makes the runtime's child processes end with the runtime, however the runtime ends (SCRUM-22).
 *
 * <p>On Windows the runtime moves into a {@link WindowsJobObject} that only it holds. Every process it
 * starts later is created inside that job, whoever starts it (the ACP SDK starts the adapters itself),
 * and so are their descendants, also grandchildren whose parent already exited. When the runtime
 * process ends, Windows kills all of them. This matters because the runtime can die without running
 * any code: an Electron shell puts it into libuv's own kill-on-close job, so when the shell is killed
 * without a tree kill, Windows kills the runtime at once. libuv's job lets children break away
 * silently, so without the runtime's own job its children would be left running.
 *
 * <p>Elsewhere than on Windows this does nothing: only {@link ProcessTreeKiller} on an orderly shutdown
 * kills the children, and only those still in the runtime's process tree.
 */
public final class ChildProcessContainment {

    private static final Logger log = LoggerFactory.getLogger(ChildProcessContainment.class);
    private static final boolean WINDOWS =
            System.getProperty("os.name").toLowerCase(Locale.ROOT).startsWith("windows");

    /** Guarded by the class. */
    private static boolean attempted;

    /** Guarded by the class. */
    private static boolean contained;

    private ChildProcessContainment() {
    }

    /**
     * Contains the children this process starts from now on. Call it before the first child starts;
     * later calls do nothing.
     *
     * @return whether children are contained: {@code false} off Windows and when Windows refused
     */
    public static synchronized boolean enable() {
        if (attempted) {
            return contained;
        }
        attempted = true;
        if (!WINDOWS) {
            return false;
        }
        try {
            WindowsJobObject.containCurrentProcess();
            contained = true;
            log.info("Child processes end with the runtime: it runs in its own job object");
        } catch (IOException | RuntimeException e) {
            log.warn("Could not create the runtime's job object; child processes are killed only on an "
                    + "orderly shutdown", e);
        }
        return contained;
    }
}
