package agentsroom.runtime.session;

import agentsroom.contract.AgentSnapshot;
import agentsroom.contract.AgentStatus;
import agentsroom.contract.AgentSummary;
import agentsroom.contract.Readiness;
import agentsroom.runtime.agent.AgentConfig;
import agentsroom.runtime.agent.HarnessCatalog;
import java.util.List;

/** How an AgentConfig looks on the wire: its {@code AgentSummary}, and its snapshot before any start. */
public final class AgentSummaries {

    private AgentSummaries() {
    }

    /**
     * An AgentConfig (no secrets) plus what the runtime derives: the readiness, and what the static
     * harness catalog says the harness reports (ux.md R3, R4).
     */
    public static AgentSummary summary(AgentConfig agent, Readiness readiness) {
        AgentSummary summary = new AgentSummary();
        summary.setAgentId(agent.agentId());
        summary.setProjectId(agent.projectId());
        summary.setName(agent.name());
        summary.setHarnessId(agent.harnessId());
        summary.setProviderId(agent.providerId());
        summary.setModelId(agent.modelId());
        summary.setAuthMethodId(agent.authMethodId());
        summary.setReadiness(readiness);
        summary.setPalette(agent.palette());
        summary.setHueShift(agent.hueShift());
        summary.setReportsToolDetails(HarnessCatalog.reportsToolDetails(agent));
        summary.setReportsSubagentSessions(HarnessCatalog.reportsSubagentSessions(agent));
        return summary;
    }

    /** An agent that never started: {@code offline}, no session, run, tool calls, permissions or events. */
    public static AgentSnapshot offline(AgentSummary summary) {
        AgentSnapshot snapshot = new AgentSnapshot();
        snapshot.setAgent(summary);
        snapshot.setStatus(AgentStatus.OFFLINE);
        snapshot.setSessionId(null);
        snapshot.setRunId(null);
        snapshot.setLastSeq(0L);
        snapshot.setToolCalls(List.of());
        snapshot.setPendingPermissions(List.of());
        snapshot.setRecentEvents(List.of());
        return snapshot;
    }
}
