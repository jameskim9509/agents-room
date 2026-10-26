package agentsroom.runtime.acp;

import com.agentclientprotocol.sdk.json.AcpJsonMapper;
import java.time.Clock;
import org.springframework.stereotype.Component;

/** Opens {@link AcpSdkConnection}s: the official ACP Java SDK behind the runtime's port. */
@Component
public class AcpSdkConnectionFactory implements AgentConnectionFactory {

    /** Without a jar manifest (tests, an IDE run) the client reports this version. */
    private static final String DEVELOPMENT_VERSION = "dev";

    private final Clock clock;
    private final AcpJsonMapper mapper = AcpJsonMapper.createDefault();
    private final String clientVersion;

    public AcpSdkConnectionFactory(Clock clock) {
        this.clock = clock;
        String version = AcpSdkConnectionFactory.class.getPackage().getImplementationVersion();
        this.clientVersion = version == null ? DEVELOPMENT_VERSION : version;
    }

    @Override
    public AgentConnection open(LaunchSpec spec, AgentConnectionListener listener) throws AgentCallException {
        return AcpSdkConnection.open(spec, listener, clock, mapper, clientVersion);
    }
}
