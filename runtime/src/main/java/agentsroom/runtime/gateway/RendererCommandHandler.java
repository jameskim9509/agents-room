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
 *   <li>connect → {@code snapshot} to that renderer (D4: on every connect)
 *   <li>{@code openProject {path}} → on success {@code projectOpened} then {@code agentsUpdated} to every
 *       renderer (the current project changed for all of them), published as one state change; on
 *       failure {@code projectOpenFailed} to the sender only
 *   <li>every other command → {@code commandRejected} to the sender: {@code UNKNOWN_PROJECT} or
 *       {@code UNKNOWN_AGENT} when it names an id the runtime does not know, else
 *       {@code NOT_IMPLEMENTED} (the later PRs wire them)
 *   <li>a frame that is no valid command → {@code commandRejected {reason: INVALID_MESSAGE}}
 * </ul>
 *
 * <p>The checks run in that order (shape, ids, then whether the command is wired), and a rejected
 * command changes nothing. Agent commands may name only the current project's agents.
 */
@Component
class RendererCommandHandler implements FrameHandler {

    private final WorkspaceService workspace;
    private final RendererGateway gateway;

    RendererCommandHandler(WorkspaceService workspace, RendererGateway gateway) {
        this.workspace = workspace;
        this.gateway = gateway;
    }

    @Override
    public void onConnect(RendererConnection connection) {
        connection.send(RendererFrames.snapshot(workspace.snapshot()));
    }

    @Override
    public void onCommand(RendererConnection connection, ClientMessage command) {
        switch (command) {
            case OpenProject open -> openProject(connection, open);
            case RelocateProject relocate ->
                    notYetImplemented(connection, CommandEcho.project(relocate.getType(), relocate.getProjectId()));
            case SaveAgentConfig save -> notYetImplemented(connection, new CommandEcho(
                    save.getType(), save.getConfig().getAgentId(), save.getConfig().getProjectId(), null));
            case CheckConnection check ->
                    notYetImplemented(connection, CommandEcho.agent(check.getType(), check.getAgentId()));
            case StartAgent start ->
                    notYetImplemented(connection, CommandEcho.agent(start.getType(), start.getAgentId()));
            case StopAgent stop ->
                    notYetImplemented(connection, CommandEcho.agent(stop.getType(), stop.getAgentId()));
            case SendPrompt prompt ->
                    notYetImplemented(connection, CommandEcho.agent(prompt.getType(), prompt.getAgentId()));
            case CancelRun cancel ->
                    notYetImplemented(connection, CommandEcho.agent(cancel.getType(), cancel.getAgentId()));
            // No permission is ever pending before agents run, so there is no permissionId to check yet.
            case RespondPermission respond ->
                    notYetImplemented(connection, CommandEcho.permission(respond.getType(), respond.getPermissionId()));
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
        // The slow part (validation, file tree) runs before the publish step, so it never holds up
        // the snapshot of a renderer that connects meanwhile.
        switch (workspace.prepareOpen(command.getPath())) {
            case ProjectOpening.Ready ready -> gateway.publish(() -> {
                OpenedProject opened = workspace.open(ready);
                return List.of(RendererFrames.projectOpened(opened.project()),
                        RendererFrames.agentsUpdated(opened.agents()));
            });
            case ProjectOpening.Refused refused ->
                    connection.send(RendererFrames.projectOpenFailed(refused.path(), refused.code(), refused.message()));
        }
    }

    /** A contract command this runtime does not wire yet: its ids are still checked first. */
    private void notYetImplemented(RendererConnection connection, CommandEcho echo) {
        if (echo.projectId() != null && !workspace.isKnownProject(echo.projectId())) {
            reject(connection, echo, RejectReason.UNKNOWN_PROJECT, "No project " + echo.projectId());
            return;
        }
        if (echo.agentId() != null) {
            Optional<AgentConfig> agent = workspace.findAgent(echo.agentId());
            // saveAgentConfig names both: the agent must belong to that project.
            boolean known = agent.isPresent()
                    && (echo.projectId() == null || echo.projectId().equals(agent.get().projectId()));
            if (!known) {
                reject(connection, echo, RejectReason.UNKNOWN_AGENT,
                        "No agent " + echo.agentId() + " in the current project");
                return;
            }
        }
        reject(connection, echo, RejectReason.NOT_IMPLEMENTED,
                "This runtime does not handle '" + echo.command() + "' yet");
    }

    private static void reject(RendererConnection connection, CommandEcho echo, RejectReason reason, String message) {
        connection.send(RendererFrames.commandRejected(echo, reason, message));
    }
}
