package agentsroom.runtime.shell;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class ShellWatchdogTests {

    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    private final BlockingQueue<String> reasons = new LinkedBlockingQueue<>();
    private final CountDownLatch releaseStdin = new CountDownLatch(1);

    @AfterEach
    void releaseBlockedReaders() {
        releaseStdin.countDown();
    }

    @Test
    void stdinEndOfFileEndsTheRuntimeEvenAfterSomeInput() throws InterruptedException {
        InputStream stdin = new ByteArrayInputStream("ignored".getBytes(StandardCharsets.UTF_8));

        new ShellWatchdog(stdin, Optional.empty(), reasons::add).start();

        assertThat(reasons.poll(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)).isEqualTo(ShellWatchdog.STDIN_CLOSED);
    }

    @Test
    void parentExitEndsTheRuntimeWhileStdinStaysOpen() throws InterruptedException {
        CompletableFuture<ProcessHandle> parentExit = new CompletableFuture<>();
        new ShellWatchdog(openStdin(), Optional.of(parentExit), reasons::add).start();
        assertThat(reasons.poll(200, TimeUnit.MILLISECONDS)).isNull();

        parentExit.complete(ProcessHandle.current());

        assertThat(reasons.poll(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)).isEqualTo(ShellWatchdog.PARENT_EXITED);
    }

    @Test
    void firesOnlyOnceWhenBothHappen() throws InterruptedException {
        CompletableFuture<ProcessHandle> parentExit = CompletableFuture.completedFuture(ProcessHandle.current());

        new ShellWatchdog(InputStream.nullInputStream(), Optional.of(parentExit), reasons::add).start();

        assertThat(reasons.poll(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)).isNotNull();
        assertThat(reasons.poll(500, TimeUnit.MILLISECONDS)).isNull();
        assertThat(List.copyOf(reasons)).isEmpty();
    }

    /** A stdin that blocks until the test ends, like the shell's open pipe. */
    private InputStream openStdin() {
        return new InputStream() {
            @Override
            public int read() {
                try {
                    releaseStdin.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return -1;
            }
        };
    }
}
