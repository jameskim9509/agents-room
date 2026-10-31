package agentsroom.runtime.process;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The job object of one adapter's process tree, nested in the runtime's job ({@link
 * ChildProcessContainment#adopt}). It lets no process go, except while a sign-in of this adapter is in
 * progress, and at most for the release's limit ({@link #releaseNewProcesses}): the processes its tree
 * creates meanwhile leave this job and the runtime's, so the browser a login page opened outlives the app
 * (SCRUM-65). Processes created before or after, and every other adapter's, stay contained.
 *
 * <p>Thread-safe: a sign-in waits on one thread while a stop can close the job on another, and the limit
 * ends a release on a timer thread.
 */
public final class AdapterJob implements AutoCloseable {

    /** The job of an adapter that is not contained (not on Windows, or Windows refused): it never releases. */
    static final AdapterJob NONE = new AdapterJob(null, 0);

    private static final Logger log = LoggerFactory.getLogger(AdapterJob.class);

    private static final ScheduledExecutorService LIMITS = Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().daemon().name("agents-room-sign-in-limit").factory());

    private final WindowsJobObject job;
    private final long pid;

    /** Guarded by this. */
    private final Set<Release> open = new LinkedHashSet<>();
    private boolean closed;

    AdapterJob(WindowsJobObject job, long pid) {
        this.job = job;
        this.pid = pid;
    }

    /** The job of an adapter whose process is unknown: it never releases. */
    public static AdapterJob none() {
        return NONE;
    }

    /**
     * Lets the processes this adapter's tree creates from now on leave the jobs, until the returned
     * release is closed or {@code limit} passed, whichever comes first. Releases of several adapters at
     * once are counted, and the runtime's job contains again when the last one ended.
     *
     * @param limit how long the release lasts at most: long enough for the login page's browser to start
     * @return the release; one that lets nothing go when this job cannot (see {@link #none})
     */
    public synchronized Release releaseNewProcesses(Duration limit) {
        Release release = new Release(limit);
        if (job == null || closed) {
            return release;
        }
        if (open.isEmpty()) {
            try {
                job.setLimits(WindowsJobObject.SILENT_BREAKAWAY_OK);
            } catch (IOException e) {
                log.warn("Could not let the sign-in of adapter process {} release its browser; it ends with the "
                        + "runtime", pid, e);
                return release;
            }
        }
        if (!ChildProcessContainment.retainRelease()) {
            if (open.isEmpty()) {
                containAgain();
            }
            return release;
        }
        open.add(release);
        release.expiry = LIMITS.schedule(() -> end(release, true), limit.toMillis(), TimeUnit.MILLISECONDS);
        log.info("Adapter process {} signs in: what its tree starts in the next {} s, until the sign-in ends, may "
                + "outlive the runtime", pid, limit.toSeconds());
        return release;
    }

    /**
     * Ends every open release and closes this process's handle on the job. The processes still in it stay
     * in the runtime's job.
     */
    @Override
    public synchronized void close() {
        if (job == null || closed) {
            return;
        }
        new ArrayList<>(open).forEach(release -> end(release, false));
        closed = true;
        job.close();
    }

    /** Ends a release once: the last open one contains again, and the runtime's job counts it out. */
    private synchronized void end(Release release, boolean expired) {
        if (!open.remove(release)) {
            return;
        }
        if (release.expiry != null) {
            release.expiry.cancel(false);
        }
        if (open.isEmpty()) {
            containAgain();
            if (expired) {
                log.info("Adapter process {} still signs in after {} s: its tree's new processes are contained "
                        + "again", pid, release.limit.toSeconds());
            } else {
                log.info("Adapter process {} no longer signs in: its tree's new processes are contained again",
                        pid);
            }
        }
        ChildProcessContainment.endRelease();
    }

    private void containAgain() {
        try {
            job.setLimits(0);
        } catch (IOException e) {
            log.warn("Could not contain the processes of adapter process {} again after its sign-in", pid, e);
        }
    }

    /** One sign-in's release; closing it a second time, or after its limit, does nothing. */
    public final class Release implements AutoCloseable {

        private final Duration limit;
        /** Ends the release at its limit; set under the job's lock. */
        private ScheduledFuture<?> expiry;

        private Release(Duration limit) {
            this.limit = limit;
        }

        @Override
        public void close() {
            end(this, false);
        }
    }
}
