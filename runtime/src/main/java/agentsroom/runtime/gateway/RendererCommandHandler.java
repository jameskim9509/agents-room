package agentsroom.runtime.gateway;

import agentsroom.contract.CancelRun;
import agentsroom.contract.CheckConnection;
import agentsroom.contract.ClientMessage;
import agentsroom.contract.OpenProject;
import agentsroom.contract.RejectReason;
import agentsroom.contract.RelocateProject;
import agentsroom.contract.RespondPermission;
import agentsroom.contract.SaveAgentConfig;
import agentsroom.contract.SendPrompt;
import agentsroom.contract.StartAgent;
import agentsroom.contract.StopAgent;
import agentsroom.runtime.agent.AgentConfig;
import agentsroom.runtime.harness.HarnessLauncher;
import agentsroom.runtime.session.AgentSupervisor;
import agentsroom.runtime.session.CommandReply;
import agentsroom.runtime.workspace.OpenedProject;
import agentsroom.runtime.workspace.ProjectOpening;
import agentsroom.runtime.workspace.WorkspaceService;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Turns renderer commands into service calls and answers with frames.
 *
 * <ul>
 *   <li>connect → {@code snapshot} to that renderer (D4: on every connect), with each agent's run-time
 *       state from the {@link AgentSupervisor}
 *   <li>{@code openProject {path}} → on success {@code projectOpened} then {@code agentsUpdated} to every
 *       renderer (the current project changed for all of them), published as one state change; on
 *       failure {@code projectOpenFailed} to the sender only. Another project opens only while every
 *       agent of the current one is idle, else {@code INVALID_STATE} (D17)
 *   <li>{@code startAgent}, {@code stopAgent}, {@code sendPrompt}, {@code checkConnection},
 *       {@code respondPermission} → the agent's controller; their effect arrives as events, a refusal
 *       ({@code INVALID_STATE}, an option the request did not offer) as {@code commandRejected}
 *   <li>{@code cancelRun}, {@code relocateProject}, {@code saveAgentConfig} → {@code NOT_IMPLEMENTED} once
 *       their ids are known (SCRUM-24, 32, 25 come later)
 *   <li>a frame that is no valid command → {@code commandRejected {reason: INVALID_MESSAGE}}
 * </ul>
 *
 * <p>The checks run in that order (shape, ids, then state or whether the command is wired; ADR 0005
 * decision 2), and a rejected command changes nothing. Agent commands may name only the current project's
 * agents. A rejection's {@code message} names no value: the ids go back in their own fields.
 */
@Component
class RendererCommandHandler implements FrameHandler {

    private final WorkspaceService workspace;
    private final RendererGateway gateway;
    private final AgentSupervisor agents;
    private final HarnessLauncher launcher;

    RendererCommandHandler(WorkspaceService workspace, RendererGateway gateway, AgentSupervisor agents,
            HarnessLauncher launcher) {
        this.workspace = workspace;
        this.gateway = gateway;
        this.agents = agents;
        this.launcher = launcher;
    }

    @Override
    public void onConnect(RendererConnection connection) {
        connection.send(RendererFrames.snapshot(workspace.snapshot(), agents::snapshot));
    }

    @Override
    public void onCommand(RendererConnection connection, ClientMessage command) {
        switch (command) {
            case OpenProject open -> openProject(connection, open);
            case RelocateProject relocate ->
                    notYetImplemented(connection, CommandEcho.project(relocate.getType(), relocate.getProjectId()));
            case SaveAgentConfig save -> notYetImplemented(connection, new CommandEcho(
                    save.getType(), save.getConfig().getAgentId(), save.getConfig().getProjectId(), null));
            case CancelRun cancel ->
                    notYetImplemented(connection, CommandEcho.agent(cancel.getType(), cancel.getAgentId()));
            case CheckConnection check -> withAgent(connection, CommandEcho.agent(check.getType(), check.getAgentId()),
                    (agent, reply) -> agents.checkConnection(agent, reply));
            case StartAgent start -> withAgent(connection, CommandEcho.agent(start.getType(), start.getAgentId()),
                    (agent, reply) -> agents.startAgent(agent, reply));
            case StopAgent stop -> withAgent(connection, CommandEcho.agent(stop.getType(), stop.getAgentId()),
                    (agent, reply) -> agents.stopAgent(agent, reply));
            case SendPrompt prompt -> withAgent(connection, CommandEcho.agent(prompt.getType(), prompt.getAgentId()),
                    (agent, reply) -> agents.sendPrompt(agent, prompt.getText(), reply));
            case RespondPermission respond -> {
                CommandEcho echo = CommandEcho.permission(respond.getType(), respond.getPermissionId());
                agents.respondPermission(respond.getPermissionId(), respond.getOptionId(), reply(connection, echo));
            }
            // The codec decodes agents-room commands only, so this is a command the handler forgot.
            default -> reject(connection, CommandEcho.of(command.getType()), RejectReason.INVALID_MESSAGE,
                    "Not an agents-room command");
        }
    }

    @Override
    public void onMalformedFrame(RendererConnection connection, MalformedFrameException error) {
        reject(connection, error.echo(), RejectReason.INVALID_MESSAGE, error.getMessage());
    }

    private void openProject(RendererConnection connection, OpenProject command) {
        // The slow part (validation, file tree, the adapter install check) runs before the publish step, so
        // it never holds up the snapshot of a renderer that connects meanwhile.
        switch (workspace.prepareOpen(command.getPath())) {
            case ProjectOpening.Ready ready -> {
                launcher.refresh();
                gateway.publish(() -> {
                    if (!agents.allowsOpening(ready.realPath())) {
                        reject(connection, CommandEcho.of(command.getType()), RejectReason.INVALID_STATE,
                                "Stop the agents of the open project before opening another one");
                        return List.of();
                    }
                    OpenedProject opened = workspace.open(ready);
                    agents.projectOpened(opened);
                    return List.of(RendererFrames.projectOpened(opened.project()), agents.agentsUpdated(opened.agents()));
                });
            }
            case ProjectOpening.Refused refused ->
                    connection.send(RendererFrames.projectOpenFailed(refused.path(), refused.code(), refused.message()));
        }
    }

    /** An agent command: the agent must be one of the current project's (else {@code UNKNOWN_AGENT}). */
    private void withAgent(RendererConnection connection, CommandEcho echo, AgentCommand command) {
        Optional<AgentConfig> agent = workspace.findAgent(echo.agentId());
        if (agent.isEmpty()) {
            reject(connection, echo, RejectReason.UNKNOWN_AGENT, "No such agent in the current project");
            return;
        }
        command.run(agent.get(), reply(connection, echo));
    }

    /** A contract command this runtime does not wire yet: its ids are still checked first. */
    private void notYetImplemented(RendererConnection connection, CommandEcho echo) {
        if (echo.projectId() != null && !workspace.isKnownProject(echo.projectId())) {
            reject(connection, echo, RejectReason.UNKNOWN_PROJECT, "No such project");
            return;
        }
        if (echo.agentId() != null) {
            Optional<AgentConfig> agent = workspace.findAgent(echo.agentId());
            // saveAgentConfig names both: the agent must belong to that project.
            boolean known = agent.isPresent()
                    && (echo.projectId() == null || echo.projectId().equals(agent.get().projectId()));
            if (!known) {
                reject(connection, echo, RejectReason.UNKNOWN_AGENT, "No such agent in the current project");
                return;
            }
        }
        reject(connection, echo, RejectReason.NOT_IMPLEMENTED, "This runtime does not handle this command yet");
    }

    /** Rejections from the agent's mailbox, later and on another thread, still go to this renderer. */
    private static CommandReply reply(RendererConnection connection, CommandEcho echo) {
        return (reason, message, agentId) -> reject(connection,
                agentId == null || echo.agentId() != null ? echo
                        : new CommandEcho(echo.command(), agentId, echo.projectId(), echo.permissionId()),
                reason, message);
    }

    private static void reject(RendererConnection connection, CommandEcho echo, RejectReason reason, String message) {
        connection.send(RendererFrames.commandRejected(echo, reason, message));
    }

    @FunctionalInterface
    private interface AgentCommand {
        void run(AgentConfig agent, CommandReply reply);
    }
}
