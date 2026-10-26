package agentsroom.runtime.acp;

import agentsroom.contract.AgentInfo;
import agentsroom.contract.AuthMethodInfo;
import java.util.List;

/**
 * The adapter's {@code initialize} answer, masked.
 *
 * @param protocolVersion the version the adapter answered with; this runtime speaks only 1 (ADR 0003
 *     decision 1), and the SDK does not check it
 * @param agentInfo {@code null} when the adapter sent none
 * @param authMethods as advertised, in order
 * @param pushesAuthStatus whether the adapter advertised {@code agentCapabilities._meta.authStatus},
 *     i.e. it pushes {@code _auth/status_update} (codex-acp, claude-agent-acp)
 */
public record AgentHandshake(
        int protocolVersion, AgentInfo agentInfo, List<AuthMethodInfo> authMethods, boolean pushesAuthStatus) {

    public AgentHandshake {
        authMethods = List.copyOf(authMethods);
    }

    /** Whether the adapter offers the auth method with this id. */
    public boolean offers(String authMethodId) {
        return authMethods.stream().anyMatch(method -> method.getId().equals(authMethodId));
    }
}
