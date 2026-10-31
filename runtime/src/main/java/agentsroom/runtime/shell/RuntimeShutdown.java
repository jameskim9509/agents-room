package agentsroom.runtime.shell;

import agentsroom.runtime.process.ProcessTreeKiller;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.stereotype.Component;

/**
 * Ends the runtime process: closes the Spring context (which stops adapters and kills their trees),
 * then exits. If closing takes longer than {@link #GRACE_PERIOD}, it kills the remaining descendants
 * (but not a sign-in's browser, {@link ProcessTreeKiller#killDescendants}) and halts, so a stuck bean can
 * never keep an orphaned runtime alive.
 */
@Component
public class RuntimeShutdown {

    static final Duration GRACE_PERIOD = Duration.ofSeconds(5);
    static final int EXIT_GRACE_PERIOD_EXCEEDED = 3;

    private static final Duration FINAL_KILL_WAIT = Duration.ofSeconds(2);
    private static final Logger log = LoggerFactory.getLogger(RuntimeShutdown.class);

    private final ConfigurableApplicationContext context;
    private final AtomicBoolean started = new AtomicBoolean();

    RuntimeShutdown(ConfigurableApplicationContext context) {
        this.context = context;
    }

    /** Starts the shutdown and returns at once. Later calls do nothing. */
    public void shutdown(String reason) {
        if (!started.compareAndSet(false, true)) {
            return;
        }
        log.info("Shutting down: {}", reason);
        Thread.ofPlatform().daemon().name("agents-room-shutdown-deadline").start(RuntimeShutdown::haltAfterGrace);
        Thread.ofPlatform().name("agents-room-shutdown").start(() -> System.exit(SpringApplication.exit(context)));
    }

    private static void haltAfterGrace() {
        try {
            Thread.sleep(GRACE_PERIOD);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        log.error("Shutdown took longer than {}; killing child processes and halting", GRACE_PERIOD);
        ProcessTreeKiller.killDescendants(ProcessHandle.current(), FINAL_KILL_WAIT);
        Runtime.getRuntime().halt(EXIT_GRACE_PERIOD_EXCEEDED);
    }
}
