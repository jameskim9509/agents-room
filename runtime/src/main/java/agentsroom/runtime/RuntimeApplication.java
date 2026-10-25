package agentsroom.runtime;

import agentsroom.runtime.config.RuntimeEnvironment;
import agentsroom.runtime.logging.MaskingOutputStream;
import agentsroom.runtime.logging.SecretMasker;
import agentsroom.runtime.shell.ControlChannel;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * The agents-room local runtime (ADR 0002). The desktop shell starts it as a child process and talks
 * to it over two paths (ADR 0001 decisions 4 and 5): stdio for the control path, a token-gated
 * WebSocket on 127.0.0.1 for the renderer.
 */
@SpringBootApplication
public class RuntimeApplication {

    /** Reactor Netty access logs would print request URIs, and the renderer's URI carries the token. */
    private static final String ACCESS_LOG_PROPERTY = "reactor.netty.http.server.accessLogEnabled";

    public static void main(String[] args) {
        // All of stderr passes the masking layer, also what bypasses logback (ADR 0004 decision 8).
        System.setErr(MaskingOutputStream.stderr(System.err.charset()));
        // stdout is the control path: it carries the ready line and nothing else. Stray System.out
        // output goes to the masked stderr.
        ControlChannel.claimStdout();
        // Uncaught exceptions go through logback too, instead of the JVM's plain stack trace.
        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> LoggerFactory.getLogger(RuntimeApplication.class)
                .error("Uncaught exception in thread {}", thread.getName(), error));
        // Mask the token before anything can log it. RuntimeEnvironment validates it later.
        SecretMasker.registerSecret(System.getenv(RuntimeEnvironment.TOKEN));
        System.setProperty(ACCESS_LOG_PROPERTY, "false");
        SpringApplication.run(RuntimeApplication.class, args);
    }
}
