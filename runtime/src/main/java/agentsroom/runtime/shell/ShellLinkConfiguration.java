package agentsroom.runtime.shell;

import agentsroom.runtime.process.ChildProcessContainment;
import agentsroom.runtime.process.ProcessTreeKiller;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The runtime as the shell's child process: it ends itself when the shell goes away, and on the way
 * out it kills its live descendants (ADR 0001 decision 5, SCRUM-22). On Windows it first moves into its
 * own job object ({@link ChildProcessContainment}), so that its children end with it even when it is
 * killed and runs no code.
 *
 * <p>On by default. Tests that run the application context inside the test JVM turn it off with
 * {@value #ENABLED_PROPERTY}{@code =false}: there, stdin, the parent and the process itself belong to
 * the test runner.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = ShellLinkConfiguration.ENABLED_PROPERTY, havingValue = "true", matchIfMissing = true)
public class ShellLinkConfiguration {

    public static final String ENABLED_PROPERTY = "agentsroom.runtime.shell-link.enabled";

    private static final Logger log = LoggerFactory.getLogger(ShellLinkConfiguration.class);

    @Bean
    SmartLifecycle shellWatchdogLifecycle(RuntimeShutdown shutdown) {
        return new ShellWatchdogLifecycle(shutdown);
    }

    /**
     * Child process cleanup. Created during startup, before any child process exists, so the job object
     * of {@link ChildProcessContainment} holds every child.
     */
    @Bean
    DisposableBean childProcessReaper() {
        ChildProcessContainment.enable();
        return new ChildProcessReaper();
    }

    private static final class ShellWatchdogLifecycle implements SmartLifecycle {

        private final RuntimeShutdown shutdown;
        private volatile boolean running;

        ShellWatchdogLifecycle(RuntimeShutdown shutdown) {
            this.shutdown = shutdown;
        }

        @Override
        public void start() {
            Optional<ProcessHandle> parent = ProcessHandle.current().parent();
            if (parent.isEmpty()) {
                log.warn("Parent process is not visible; only stdin EOF will end the runtime");
            }
            Optional<CompletableFuture<?>> parentExit = parent.map(ProcessHandle::onExit);
            new ShellWatchdog(System.in, parentExit, shutdown::shutdown).start();
            running = true;
        }

        @Override
        public void stop() {
            // The watchdog thread is a daemon blocked on stdin; it dies with the JVM.
            running = false;
        }

        @Override
        public boolean isRunning() {
            return running;
        }
    }

    /**
     * On an orderly exit, kills every live descendant but a web browser a sign-in released (the user's,
     * {@link ProcessTreeKiller#killDescendants}). A process whose parent already exited is no
     * longer a descendant: on Windows the job object of {@link ChildProcessContainment} kills it when the
     * runtime process ends; elsewhere it can be left running.
     */
    private static final class ChildProcessReaper implements DisposableBean {

        private static final Duration KILL_WAIT = Duration.ofSeconds(2);

        @Override
        public void destroy() {
            if (!ProcessTreeKiller.killDescendants(ProcessHandle.current(), KILL_WAIT)) {
                log.warn("Some child processes were still alive {} after being killed", KILL_WAIT);
            }
        }
    }
}
