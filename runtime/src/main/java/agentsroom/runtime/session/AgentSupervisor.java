package agentsroom.runtime.session;

import agentsroom.contract.AgentSnapshot;
import agentsroom.contract.AgentSummary;
import agentsroom.contract.AgentsUpdated;
import agentsroom.contract.Readiness;
import agentsroom.contract.RejectReason;
import agentsroom.contract.ServerMessage;
import agentsroom.runtime.acp.AgentConnectionFactory;
import agentsroom.runtime.agent.AgentConfig;
import agentsroom.runtime.agent.HarnessCatalog;
import agentsroom.runtime.harness.HarnessLauncher;
import agentsroom.runtime.permission.PermissionBroker;
import agentsroom.runtime.workspace.OpenedProject;
import agentsroom.runtime.workspace.WorkspaceService;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.stream.Collectors;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.stereotype.Service;

/**
 * The agents of the current project at run time (architecture.md: Process supervisor, ACP client +
 * session manager, Event normalizer). One {@link AgentController} per agent; this class routes the
 * renderer's agent commands to it, answers the gateway's snapshot and readiness questions, and keeps
 * the rule that only one project is open: another project opens only while every agent of the current
 * one is idle (D17).
 *
 * <p>The read methods run under the gateway's publish lock (inside a snapshot or a publish step).
 */
@Service
public class AgentSupervisor implements DisposableBean {

    private final WorkspaceService workspace;
    private final AgentServices services;
    private final Map<String, AgentController> controllers = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler;

    /** The agent list renderers last got. Guarded by the publish lock. */
    private List<AgentSummary> shown = List.of();

    AgentSupervisor(StatePublisher publisher, WorkspaceService workspace, HarnessLauncher launcher,
            AgentConnectionFactory connections, PermissionBroker broker, AgentTimeouts timeouts, Clock clock) {
        this.workspace = workspace;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(
                Thread.ofPlatform().daemon().name("agents-room-agent-timers").factory());
        this.services = new AgentServices(publisher, launcher, connections, broker, timeouts, clock, scheduler,
                this::summariesChanged);
    }

    // ── Gateway reads (under the publish lock) ────────────────────────

    /** The agent for a snapshot frame. */
    public AgentSnapshot snapshot(AgentConfig agent) {
        AgentController controller = controllers.get(agent.agentId());
        AgentSummary summary = AgentSummaries.summary(agent, readiness(agent));
        return controller == null ? AgentSummaries.offline(summary) : controller.snapshot(summary);
    }

    /** {@code agentsUpdated} for the current project's agents; renderers will have seen this list. */
    public AgentsUpdated agentsUpdated(List<AgentConfig> agents) {
        shown = summaries(agents);
        AgentsUpdated frame = new AgentsUpdated();
        frame.setAgents(shown);
        return frame;
    }

    /**
     * Whether a project at {@code realPath} may open now: the current one again, or another one while no
     * agent of the current project runs, stops or is being checked.
     */
    public boolean allowsOpening(Path realPath) {
        String current = workspace.currentProjectPath().orElse(null);
        if (current == null || Path.of(current).equals(realPath)) {
            return true;
        }
        return controllers.values().stream().noneMatch(AgentController::isActive);
    }

    /** The project just opened: its agents get controllers; the previous project's ones retire. */
    public void projectOpened(OpenedProject opened) {
        Path path = Path.of(opened.project().path());
        Set<String> ids = opened.agents().stream().map(AgentConfig::agentId).collect(Collectors.toSet());
        controllers.entrySet().removeIf(entry -> {
            boolean gone = !ids.contains(entry.getKey());
            if (gone) {
                entry.getValue().retire();
            }
            return gone;
        });
        for (AgentConfig agent : opened.agents()) {
            controllers.computeIfAbsent(agent.agentId(), id -> new AgentController(agent, path, services));
        }
    }

    // ── Commands (ids already checked by the gateway) ─────────────────

    public void startAgent(AgentConfig agent, CommandReply reply) {
        controller(agent).start(reply);
    }

    public void stopAgent(AgentConfig agent, CommandReply reply) {
        controller(agent).stop(reply);
    }

    public void sendPrompt(AgentConfig agent, String text, CommandReply reply) {
        controller(agent).sendPrompt(text, reply);
    }

    public void checkConnection(AgentConfig agent, CommandReply reply) {
        controller(agent).checkConnection(reply);
    }

    /** {@code respondPermission}: only a pending request of a current agent can be answered. */
    public void respondPermission(String permissionId, String optionId, CommandReply reply) {
        AgentController owner = services.broker().find(permissionId)
                .map(pending -> controllers.get(pending.agentId()))
                .orElse(null);
        if (owner == null) {
            reply.reject(RejectReason.UNKNOWN_PERMISSION, "No such pending permission request");
            return;
        }
        owner.respondPermission(permissionId, optionId, reply);
    }

    @Override
    public void destroy() {
        controllers.values().forEach(AgentController::shutdown);
        controllers.clear();
        scheduler.shutdownNow();
    }

    // ── Internals ─────────────────────────────────────────────────────

    private AgentController controller(AgentConfig agent) {
        return controllers.computeIfAbsent(agent.agentId(), id -> new AgentController(agent,
                Path.of(workspace.currentProjectPath().orElseThrow()), services));
    }

    private Readiness readiness(AgentConfig agent) {
        AgentController controller = controllers.get(agent.agentId());
        if (controller != null) {
            return controller.readiness();
        }
        if (!HarnessCatalog.supports(agent)) {
            return Readiness.UNSUPPORTED;
        }
        return services.launcher().isAvailable(agent.harnessId()) ? Readiness.CONFIGURED : Readiness.EXECUTABLE_MISSING;
    }

    private List<AgentSummary> summaries(List<AgentConfig> agents) {
        return agents.stream().map(agent -> AgentSummaries.summary(agent, readiness(agent))).toList();
    }

    /** Appends {@code agentsUpdated} when a readiness moved since renderers last got the list. */
    private void summariesChanged(List<ServerMessage> frames) {
        List<AgentSummary> now = summaries(workspace.snapshot().agents());
        if (!now.equals(shown)) {
            frames.add(agentsUpdated(workspace.snapshot().agents()));
        }
    }
}
