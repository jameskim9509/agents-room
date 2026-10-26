package agentsroom.runtime.acp;

import agentsroom.runtime.harness.AdapterEnvironment;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

/** Starts adapter processes and connects to them (ADR 0003 decision 3: one client per process). */
public interface AgentConnectionFactory {

    /**
     * What to start.
     *
     * @param argv the command line, run as is
     * @param cwd the process's working directory: the project path (D11)
     * @param environment the whole environment of the process (D12)
     * @param label how the runtime log names the process, e.g. in the prefix of its stderr lines
     * @param longestCall the longest timeout any call on this connection uses, so the SDK's own request
     *     timeout never fires first
     */
    record LaunchSpec(List<String> argv, Path cwd, AdapterEnvironment environment, String label, Duration longestCall) {

        public LaunchSpec {
            argv = List.copyOf(argv);
        }
    }

    /**
     * Starts the process and connects. Nothing is sent yet.
     *
     * @throws AgentCallException {@link AgentCallException.Kind#EXECUTABLE_MISSING} when the executable
     *     does not exist, {@link AgentCallException.Kind#START_FAILED} when it cannot start otherwise
     */
    AgentConnection open(LaunchSpec spec, AgentConnectionListener listener) throws AgentCallException;
}
