package agentsroom.runtime.process;

import java.io.IOException;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Makes the runtime's child processes end with the runtime, however the runtime ends (SCRUM-22).
 *
 * <p>On Windows the runtime moves into a {@link WindowsJobObject} with {@code KILL_ON_JOB_CLOSE} that
 * only it holds. Every process it starts later is created inside that job, whoever starts it (the ACP SDK
 * starts the adapters itself), and so are their descendants, also grandchildren whose parent already
 * exited. When the runtime process ends, Windows kills all of them. This matters because the runtime can
 * die without running any code: an Electron shell puts it into libuv's own kill-on-close job, so when the
 * shell is killed without a tree kill, Windows kills the runtime at once. libuv's job lets children break
 * away silently, so without the runtime's own job its children would be left running.
 *
 * <p>A sign-in is the one exception (SCRUM-65): codex-acp opens the ChatGPT login page from the adapter's
 * own process, and a browser that was not running yet starts as the adapter's descendant, so the job
 * would kill the user's browser with the app. Every adapter therefore runs in a job of its own nested in
 * the runtime's ({@link #adopt}), and while one of them signs in, for a limited time
 * ({@link AdapterJob#releaseNewProcesses}), both its job and the runtime's let new processes break away:
 * what that adapter's tree starts meanwhile (the browser) leaves the jobs. The runtime's job counts the
 * sign-ins in progress and contains again once the last one ended; the other adapters' jobs never let a
 * process go, so their processes stay contained during someone else's sign-in. A process that left is
 * "released" ({@link #isReleased}): the runtime's kills spare it when it is a web browser (the user's,
 * {@link WebBrowsers}) and kill any other one that is still among the processes they reach.
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

    /** The runtime's job, or {@code null} when children are not contained. Guarded by the class. */
    private static WindowsJobObject runtimeJob;

    /** Sign-ins whose release is open; the runtime's job lets processes go while there is one. Guarded by the class. */
    private static int releases;

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
            return runtimeJob != null;
        }
        attempted = true;
        if (!WINDOWS) {
            return false;
        }
        WindowsJobObject job = null;
        try {
            job = WindowsJobObject.create(WindowsJobObject.KILL_ON_JOB_CLOSE);
            // When this process already runs in a job (Electron's libuv job, for example), the new job
            // nests inside it. The handle stays open on purpose, for the rest of this process's life:
            // when the process ends, however it ends, Windows closes it and kills what is left in the job.
            job.assignCurrentProcess();
            runtimeJob = job;
            log.info("Child processes end with the runtime: it runs in its own job object");
        } catch (IOException | RuntimeException e) {
            if (job != null) {
                job.close();
            }
            log.warn("Could not create the runtime's job object; child processes are killed only on an "
                    + "orderly shutdown", e);
        }
        return runtimeJob != null;
    }

    /**
     * Gives an adapter process that just started a job of its own inside the runtime's job, together with
     * the descendants it already has. Call it right after the start, before the adapter starts children
     * of its own: a child it started before stays in the runtime's job only.
     *
     * @return the adapter's job; one that never releases anything when children are not contained or
     *     Windows refused
     */
    public static AdapterJob adopt(ProcessHandle adapter) {
        WindowsJobObject runtime = runtimeJob();
        if (runtime == null) {
            return AdapterJob.NONE;
        }
        WindowsJobObject job = null;
        try {
            // Started while another adapter signs in, it left the runtime's job at once: bring it back.
            if (!runtime.contains(adapter.pid())) {
                runtime.assign(adapter.pid());
            }
            job = WindowsJobObject.create(0);
            job.assign(adapter.pid());
            for (ProcessHandle descendant : adapter.descendants().toList()) {
                try {
                    if (!job.contains(descendant.pid())) {
                        job.assign(descendant.pid());
                    }
                } catch (IOException e) {
                    // Gone, or already in a job of its own: it stays in the runtime's job.
                    log.debug("Process {} stays outside the job of adapter {}: {}", descendant.pid(), adapter.pid(),
                            e.getMessage());
                }
            }
            return new AdapterJob(job, adapter.pid());
        } catch (IOException | RuntimeException e) {
            if (job != null) {
                job.close();
            }
            if (adapter.isAlive()) {
                log.warn("Could not give adapter process {} a job of its own; a sign-in browser it opens ends "
                        + "with the runtime", adapter.pid(), e);
            } else {
                log.debug("Adapter process {} ended before it got a job of its own", adapter.pid());
            }
            return AdapterJob.NONE;
        }
    }

    /**
     * Whether a process left the runtime's job during a sign-in (or was never in it): such a process is
     * no longer the runtime's, and no kill of the runtime's processes takes it. Always {@code false}
     * when the runtime's children are not contained, or when the process cannot be looked at (it is
     * gone, for one).
     */
    public static boolean isReleased(ProcessHandle process) {
        WindowsJobObject runtime = runtimeJob();
        if (runtime == null || process.equals(ProcessHandle.current())) {
            return false;
        }
        try {
            return !runtime.contains(process.pid());
        } catch (IOException e) {
            return false;
        }
    }

    private static synchronized WindowsJobObject runtimeJob() {
        return runtimeJob;
    }

    /**
     * One more sign-in lets the processes it starts leave: the first one opens the runtime's job.
     *
     * @return whether the runtime's job lets processes go now
     */
    static synchronized boolean retainRelease() {
        if (runtimeJob == null) {
            return false;
        }
        if (releases == 0) {
            try {
                runtimeJob.setLimits(WindowsJobObject.KILL_ON_JOB_CLOSE | WindowsJobObject.SILENT_BREAKAWAY_OK);
            } catch (IOException e) {
                log.warn("Could not let a sign-in's processes leave the runtime's job object", e);
                return false;
            }
        }
        releases++;
        return true;
    }

    /** A sign-in that {@link #retainRelease retained} ended: the last one contains again. */
    static synchronized void endRelease() {
        if (releases == 0) {
            return;
        }
        releases--;
        if (releases == 0) {
            try {
                runtimeJob.setLimits(WindowsJobObject.KILL_ON_JOB_CLOSE);
                log.info("No sign-in in progress: the runtime's job object holds every new process again");
            } catch (IOException e) {
                log.error("Could not contain the runtime's child processes again after a sign-in; processes "
                        + "started from now on may outlive the runtime", e);
            }
        }
    }
}
