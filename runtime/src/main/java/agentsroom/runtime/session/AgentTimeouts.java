package agentsroom.runtime.session;

import java.time.Duration;
import org.springframework.core.env.Environment;

/**
 * The time limits of an agent's ACP calls (D14). There is no limit on a prompt: a run ends only with
 * the adapter's answer (ADR 0005 decision 5). Each can be changed with its property (tests use short
 * ones), e.g. {@code agentsroom.runtime.acp.initialize-timeout=2s}.
 *
 * @param initialize {@code initialize}; past it, {@code ACP_INIT_FAILED} (a cold codex-acp answers in
 *     2–3 s)
 * @param authStatusWait how long to wait for the adapter's first {@code _auth/status_update} after
 *     {@code initialize} (codex-acp pushes it right after its answer)
 * @param authenticate {@code authenticate}, which waits for the user's browser sign-in; past it,
 *     {@code AUTH_FAILED}. codex-acp itself never gives up on a sign-in
 * @param sessionNew {@code session/new}; past it, {@code SESSION_CREATE_FAILED}
 * @param stopCancelWait how long {@code stopAgent} waits for the {@code session/prompt} answer after
 *     {@code session/cancel} before it ends the process
 * @param close how long ending a process may take: stdin EOF, the adapter's own exit, then the tree kill
 */
public record AgentTimeouts(
        Duration initialize,
        Duration authStatusWait,
        Duration authenticate,
        Duration sessionNew,
        Duration stopCancelWait,
        Duration close) {

    static final String PREFIX = "agentsroom.runtime.acp.";

    public static final AgentTimeouts DEFAULTS = new AgentTimeouts(Duration.ofSeconds(30), Duration.ofSeconds(3),
            Duration.ofMinutes(5), Duration.ofSeconds(60), Duration.ofSeconds(5), Duration.ofSeconds(10));

    static AgentTimeouts from(Environment environment) {
        return new AgentTimeouts(
                read(environment, "initialize-timeout", DEFAULTS.initialize()),
                read(environment, "auth-status-wait", DEFAULTS.authStatusWait()),
                read(environment, "authenticate-timeout", DEFAULTS.authenticate()),
                read(environment, "session-new-timeout", DEFAULTS.sessionNew()),
                read(environment, "stop-cancel-wait", DEFAULTS.stopCancelWait()),
                read(environment, "close-timeout", DEFAULTS.close()));
    }

    /** The longest call, so that the SDK's own request timeout never fires before these. */
    Duration longestCall() {
        Duration longest = initialize;
        for (Duration duration : new Duration[] {authenticate, sessionNew}) {
            if (duration.compareTo(longest) > 0) {
                longest = duration;
            }
        }
        return longest;
    }

    private static Duration read(Environment environment, String name, Duration fallback) {
        return environment.getProperty(PREFIX + name, Duration.class, fallback);
    }
}
