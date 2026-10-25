package agentsroom.runtime.gateway;

import agentsroom.runtime.workspace.OpenedProject;
import agentsroom.runtime.workspace.ProjectOpening;
import agentsroom.runtime.workspace.WorkspaceService;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Turns renderer commands into service calls and answers with frames.
 *
 * <ul>
 *   <li>connect → {@code snapshot} to that renderer (D4: on every connect)
 *   <li>{@code openProject {path}} → on success {@code projectOpened} then {@code agentsUpdated} to every
 *       renderer (the current project changed for all of them), published as one state change; on
 *       failure {@code projectOpenFailed} to the sender only
 *   <li>anything else → {@code commandRejected} to the sender
 * </ul>
 *
 * <p>TODO(contract): switch over the generated {@code ClientMessage} types instead of type strings.
 */
@Component
class RendererCommandHandler implements FrameHandler {

    static final String OPEN_PROJECT = "openProject";
    static final String UNKNOWN_COMMAND = "unknown";

    /** Commands of the contract that later PRs implement (ACP, permissions, cancel, relocate, config). */
    static final Set<String> NOT_YET_IMPLEMENTED = Set.of(
            "startAgent", "stopAgent", "sendPrompt", "checkConnection", "respondPermission", "cancelRun",
            "relocateProject", "saveAgentConfig");

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
    public void onFrame(RendererConnection connection, ClientFrame frame) {
        if (OPEN_PROJECT.equals(frame.type())) {
            openProject(connection, frame);
        } else if (NOT_YET_IMPLEMENTED.contains(frame.type())) {
            connection.send(RendererFrames.commandRejected(frame.type(), RendererFrames.REJECT_NOT_IMPLEMENTED,
                    "This runtime does not handle '" + frame.type() + "' yet"));
        } else {
            connection.send(RendererFrames.commandRejected(frame.type(), RendererFrames.REJECT_INVALID_MESSAGE,
                    "Unknown command"));
        }
    }

    @Override
    public void onMalformedFrame(RendererConnection connection, MalformedFrameException error) {
        connection.send(RendererFrames.commandRejected(UNKNOWN_COMMAND, RendererFrames.REJECT_INVALID_MESSAGE,
                error.getMessage()));
    }

    private void openProject(RendererConnection connection, ClientFrame frame) {
        Optional<String> path = frame.string("path");
        if (path.isEmpty()) {
            connection.send(RendererFrames.commandRejected(OPEN_PROJECT, RendererFrames.REJECT_INVALID_MESSAGE,
                    "openProject needs a string 'path'"));
            return;
        }
        // The slow part (validation, file tree) runs before the publish step, so it never holds up
        // the snapshot of a renderer that connects meanwhile.
        switch (workspace.prepareOpen(path.get())) {
            case ProjectOpening.Ready ready -> gateway.publish(() -> {
                OpenedProject opened = workspace.open(ready);
                return List.of(RendererFrames.projectOpened(opened.project()),
                        RendererFrames.agentsUpdated(opened.agents()));
            });
            case ProjectOpening.Refused refused ->
                    connection.send(RendererFrames.projectOpenFailed(refused.path(), refused.code(), refused.message()));
        }
    }
}
