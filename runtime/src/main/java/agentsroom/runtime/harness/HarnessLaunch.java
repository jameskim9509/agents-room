package agentsroom.runtime.harness;

import java.util.List;
import java.util.Map;

/** How to start a harness's ACP adapter, or why it cannot be started (D11). */
public sealed interface HarnessLaunch {

    /**
     * The adapter can be started.
     *
     * @param argv the command line: an executable and its arguments, run as is (no shell)
     * @param origin where the command came from, for the log: the override variable or the installed
     *     adapter
     * @param environment variables the launch itself adds to the adapter's allowlisted environment:
     *     {@code ELECTRON_RUN_AS_NODE=1} when the executable is the shell's Electron (ADR 0003
     *     decision 12), otherwise none. Never secrets, so they may be logged
     */
    record Ready(List<String> argv, String origin, Map<String, String> environment) implements HarnessLaunch {

        public Ready {
            argv = List.copyOf(argv);
            environment = Map.copyOf(environment);
        }

        /** A launch that adds nothing to the environment. */
        public Ready(List<String> argv, String origin) {
            this(argv, origin, Map.of());
        }
    }

    /**
     * The adapter cannot be started: not installed, no usable {@code node}, a malformed override, or the
     * e2e fail-closed rule.
     *
     * @param reason diagnostic detail (which file or variable is missing), not UI copy
     */
    record Unavailable(String reason) implements HarnessLaunch {
    }
}
