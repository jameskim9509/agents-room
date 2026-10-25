package agentsroom.runtime.gateway;

import agentsroom.contract.AgentSnapshot;
import agentsroom.contract.AgentStatus;
import agentsroom.contract.AgentSummary;
import agentsroom.contract.AgentsUpdated;
import agentsroom.contract.CommandRejected;
import agentsroom.contract.FileEntry;
import agentsroom.contract.ProjectErrorCode;
import agentsroom.contract.ProjectInfo;
import agentsroom.contract.ProjectOpenFailed;
import agentsroom.contract.ProjectOpened;
import agentsroom.contract.Readiness;
import agentsroom.contract.RecentProject;
import agentsroom.contract.RejectReason;
import agentsroom.contract.Snapshot;
import agentsroom.runtime.agent.AgentConfig;
import agentsroom.runtime.agent.HarnessCatalog;
import agentsroom.runtime.logging.SecretMasker;
import agentsroom.runtime.workspace.WorkspaceSnapshot;
import java.util.List;

/**
 * Builds the frames the runtime sends, from domain data, as the classes generated from
 * {@code core/asyncapi.yaml}. Every diagnostic {@code message} passes the masking layer here (ADR 0004
 * decision 8, ADR 0005 decision 11).
 */
public final class RendererFrames {

    private RendererFrames() {
    }

    /** {@code snapshot}: sent first on every connect (D4). */
    public static Snapshot snapshot(WorkspaceSnapshot snapshot) {
        Snapshot frame = new Snapshot();
        frame.setCurrentProjectId(snapshot.currentProjectId());
        frame.setRecentProjects(snapshot.recentProjects().stream().map(RendererFrames::recentProject).toList());
        if (snapshot.project() != null) {
            frame.setProject(project(snapshot.project()));
        }
        frame.setAgents(snapshot.agents().stream().map(RendererFrames::agentSnapshot).toList());
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
    public static AgentsUpdated agentsUpdated(List<AgentConfig> agents) {
        AgentsUpdated frame = new AgentsUpdated();
        frame.setAgents(agents.stream().map(RendererFrames::agentSummary).toList());
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

    /**
     * An AgentConfig plus what the catalog says about its harness. Readiness is {@code configured} for
     * a pair the catalog runs, otherwise {@code unsupported}; the adapter checks that can lower it come
     * with the adapter launch (see {@link HarnessCatalog}).
     */
    private static AgentSummary agentSummary(AgentConfig agent) {
        AgentSummary summary = new AgentSummary();
        summary.setAgentId(agent.agentId());
        summary.setProjectId(agent.projectId());
        summary.setName(agent.name());
        summary.setHarnessId(agent.harnessId());
        summary.setProviderId(agent.providerId());
        summary.setModelId(agent.modelId());
        summary.setAuthMethodId(agent.authMethodId());
        summary.setReadiness(HarnessCatalog.supports(agent) ? Readiness.CONFIGURED : Readiness.UNSUPPORTED);
        summary.setPalette(agent.palette());
        summary.setHueShift(agent.hueShift());
        summary.setReportsToolDetails(HarnessCatalog.reportsToolDetails(agent));
        summary.setReportsSubagentSessions(HarnessCatalog.reportsSubagentSessions(agent));
        return summary;
    }

    /**
     * An agent at snapshot time. Nothing runs yet, so every agent is {@code offline} with no session,
     * run, tool calls, permissions or events (ADR 0005), and every optional field is left out.
     */
    private static AgentSnapshot agentSnapshot(AgentConfig agent) {
        AgentSnapshot snapshot = new AgentSnapshot();
        snapshot.setAgent(agentSummary(agent));
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
