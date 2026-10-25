package agentsroom.runtime.config;

import org.springframework.boot.diagnostics.AbstractFailureAnalyzer;
import org.springframework.boot.diagnostics.FailureAnalysis;

/** Turns a missing handshake variable into a short startup message instead of a bean-creation trace. */
class RuntimeEnvironmentFailureAnalyzer extends AbstractFailureAnalyzer<RuntimeEnvironmentException> {

    @Override
    protected FailureAnalysis analyze(Throwable rootFailure, RuntimeEnvironmentException cause) {
        return new FailureAnalysis(
                cause.getMessage(),
                "Start the runtime from the agents-room desktop shell. To run it by hand, set "
                        + RuntimeEnvironment.TOKEN + ", " + RuntimeEnvironment.DATA_DIR + " and "
                        + RuntimeEnvironment.ALLOWED_ORIGINS + " (docs/architecture.md).",
                cause);
    }
}
