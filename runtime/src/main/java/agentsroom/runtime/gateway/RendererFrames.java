package agentsroom.runtime.gateway;

import agentsroom.contract.AgentSnapshot;
import agentsroom.contract.AgentSummary;
import agentsroom.contract.AgentsUpdated;
import agentsroom.contract.CommandRejected;
import agentsroom.contract.FileEntry;
import agentsroom.contract.ProjectErrorCode;
import agentsroom.contract.ProjectInfo;
import agentsroom.contract.ProjectOpenFailed;
import agentsroom.contract.ProjectOpened;
import agentsroom.contract.RecentProject;
import agentsroom.contract.RejectReason;
import agentsroom.contract.Snapshot;
import agentsroom.runtime.agent.AgentConfig;
import agentsroom.runtime.logging.SecretMasker;
import agentsroom.runtime.workspace.WorkspaceSnapshot;
import java.util.List;
import java.util.function.Function;

/**
 * Builds the frames the runtime sends, from domain data, as the classes generated from
 * {@code core/asyncapi.yaml}. Every diagnostic {@code message} passes the masking layer here (ADR 0004
 * decision 8, ADR 0005 decision 11).
 */
public final class RendererFrames {

    private RendererFrames() {
    }

    /**
     * {@code snapshot}: sent first on every connect (D4).
     *
     * @param agentSnapshot each agent's run-time state (ADR 0005 decision 2), from the session manager
     */
    public static Snapshot snapshot(WorkspaceSnapshot snapshot, Function<AgentConfig, AgentSnapshot> agentSnapshot) {
        Snapshot frame = new Snapshot();
        frame.setCurrentProjectId(snapshot.currentProjectId());
        frame.setRecentProjects(snapshot.recentProjects().stream().map(RendererFrames::recentProject).toList());
        if (snapshot.project() != null) {
            frame.setProject(project(snapshot.project()));
        }
        frame.setAgents(snapshot.agents().stream().map(agentSnapshot).toList());
        return frame;
    }

    public static ProjectOpened projectOpened(agentsroom.runtime.project.ProjectInfo project) {
        ProjectOpened frame = new ProjectOpened();
        frame.setProject(project(project));
        return frame;
    }

    /** @param path the path as requested; {@code null} is sent as an empty string */
    public static ProjectOpenFailed projectOpenFailed(
            String path, agentsroom.runtime.project.ProjectErrorCode code, String message) {
        ProjectOpenFailed frame = new ProjectOpenFailed();
        frame.setPath(path == null ? "" : path);
        frame.setCode(switch (code) {
            case PATH_NOT_ABSOLUTE -> ProjectErrorCode.PATH_NOT_ABSOLUTE;
            case PATH_NOT_FOUND -> ProjectErrorCode.PATH_NOT_FOUND;
            case PATH_NOT_DIRECTORY -> ProjectErrorCode.PATH_NOT_DIRECTORY;
            case PATH_NOT_READABLE -> ProjectErrorCode.PATH_NOT_READABLE;
        });
        frame.setMessage(SecretMasker.mask(message));
        return frame;
    }

    /** {@code agentsUpdated}: the current project's whole agent list. */
    public static AgentsUpdated agentsUpdated(List<AgentSummary> agents) {
        AgentsUpdated frame = new AgentsUpdated();
        frame.setAgents(List.copyOf(agents));
        return frame;
    }

    /** {@code commandRejected}: nothing changed. The ids the command carried go back with it. */
    static CommandRejected commandRejected(CommandEcho echo, RejectReason reason, String message) {
        CommandRejected frame = new CommandRejected();
        frame.setCommand(echo.command());
        frame.setReason(reason);
        frame.setMessage(SecretMasker.mask(message));
        frame.setAgentId(echo.agentId());
        frame.setProjectId(echo.projectId());
        frame.setPermissionId(echo.permissionId());
        return frame;
    }

    private static RecentProject recentProject(agentsroom.runtime.persistence.RecentProject project) {
        RecentProject recent = new RecentProject();
        recent.setProjectId(project.projectId());
        recent.setName(project.name());
        recent.setPath(project.path());
        return recent;
    }

    private static ProjectInfo project(agentsroom.runtime.project.ProjectInfo project) {
        ProjectInfo info = new ProjectInfo();
        info.setProjectId(project.projectId());
        info.setName(project.name());
        info.setPath(project.path());
        info.setFiles(project.files().stream().map(RendererFrames::fileEntry).toList());
        info.setFilesTruncated(project.filesTruncated());
        return info;
    }

    private static FileEntry fileEntry(agentsroom.runtime.project.FileEntry entry) {
        FileEntry file = new FileEntry();
        file.setRelativePath(entry.relativePath());
        file.setDirectory(entry.directory());
        return file;
    }
}
