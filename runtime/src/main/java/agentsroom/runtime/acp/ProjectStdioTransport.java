package agentsroom.runtime.acp;

import com.agentclientprotocol.sdk.client.transport.AgentParameters;
import com.agentclientprotocol.sdk.client.transport.StdioAcpClientTransport;
import com.agentclientprotocol.sdk.json.AcpJsonMapper;
import java.nio.file.Path;

/**
 * The SDK's stdio transport, with the adapter started in the project folder. {@code AgentParameters}
 * has no working directory, and this override is the documented way to set one (acp-facts §1.2). The
 * ACP session's {@code cwd} is sent separately, in {@code session/new}.
 */
final class ProjectStdioTransport extends StdioAcpClientTransport {

    private final Path cwd;

    ProjectStdioTransport(AgentParameters parameters, AcpJsonMapper mapper, Path cwd) {
        super(parameters, mapper);
        this.cwd = cwd;
    }

    @Override
    protected ProcessBuilder getProcessBuilder() {
        return new ProcessBuilder().directory(cwd.toFile());
    }
}
