package agentsroom.runtime.shell;

import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ApplicationListener;
import org.springframework.stereotype.Component;

/**
 * Tells the shell the runtime is ready: one line {@code AGENTS_ROOM_READY {"port":<int>}} on stdout,
 * written after the whole context is up (ADR 0001 decision 5).
 */
@Component
class ReadyLineAnnouncer implements ApplicationListener<ApplicationReadyEvent> {

    static final String PREFIX = "AGENTS_ROOM_READY ";

    private static final Logger log = LoggerFactory.getLogger(ReadyLineAnnouncer.class);

    private final AtomicBoolean announced = new AtomicBoolean();

    /** The ready line without its terminating {@code \n}. */
    static String readyLine(int port) {
        return PREFIX + "{\"port\":" + port + "}";
    }

    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        if (!(event.getApplicationContext() instanceof WebServerApplicationContext context)) {
            return;
        }
        if (!announced.compareAndSet(false, true)) {
            return;
        }
        int port = context.getWebServer().getPort();
        ControlChannel.writeLine(readyLine(port));
        log.info("Ready: renderer gateway listening on 127.0.0.1:{}", port);
    }
}
