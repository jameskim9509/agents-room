package agentsroom.runtime.gateway;

import agentsroom.runtime.agent.AgentConfig;
import agentsroom.runtime.persistence.RecentProject;
import agentsroom.runtime.project.FileEntry;
import agentsroom.runtime.project.ProjectErrorCode;
import agentsroom.runtime.project.ProjectInfo;
import agentsroom.runtime.workspace.WorkspaceSnapshot;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds the frames the runtime sends, from domain data.
 *
 * <p>TODO(contract): temporary. Field names follow the draft {@code core/asyncapi.yaml} of the
 * contract PR (snapshot, projectOpened, projectOpenFailed, agentsUpdated, commandRejected). Once the
 * generated bindings land, build the generated message classes here instead of maps. Still missing
 * against that draft: {@code AgentSummary.readiness}, {@code reportsToolDetails} and
 * {@code reportsSubagentSessions}, which come from the harness catalog and the adapter check (not part
 * of this skeleton).
 */
public final class RendererFrames {

    /** Every agent is {@code offline} after a restart; nothing runs yet (ADR 0005). */
    static final String STATUS_OFFLINE = "offline";

    /** RejectReason: the frame is not a valid command. */
    static final String REJECT_INVALID_MESSAGE = "INVALID_MESSAGE";

    /** RejectReason: a contract command this runtime does not handle yet. */
    static final String REJECT_NOT_IMPLEMENTED = "NOT_IMPLEMENTED";

    private RendererFrames() {
    }

    /** {@code snapshot}: sent first on every connect (D4). */
    public static ServerFrame snapshot(WorkspaceSnapshot snapshot) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("currentProjectId", snapshot.currentProjectId());
        body.put("recentProjects", snapshot.recentProjects().stream().map(RendererFrames::recentProject).toList());
        if (snapshot.project() != null) {
            body.put("project", project(snapshot.project()));
        }
        body.put("agents", snapshot.agents().stream().map(RendererFrames::agentSnapshot).toList());
        return new ServerFrame("snapshot", body);
    }

    public static ServerFrame projectOpened(ProjectInfo project) {
        return new ServerFrame("projectOpened", Map.of("project", project(project)));
    }

    public static ServerFrame projectOpenFailed(String path, ProjectErrorCode code, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("path", path == null ? "" : path);
        body.put("code", code.name());
        body.put("message", message);
        return new ServerFrame("projectOpenFailed", body);
    }

    public static ServerFrame agentsUpdated(List<AgentConfig> agents) {
        List<Map<String, Object>> summaries = agents.stream().map(RendererFrames::agentSummary).toList();
        return new ServerFrame("agentsUpdated", Map.of("agents", summaries));
    }

    public static ServerFrame commandRejected(String command, String reason, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("command", command);
        body.put("reason", reason);
        body.put("message", message);
        return new ServerFrame("commandRejected", body);
    }

    private static Map<String, Object> recentProject(RecentProject project) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("projectId", project.projectId());
        map.put("name", project.name());
        map.put("path", project.path());
        return map;
    }

    private static Map<String, Object> project(ProjectInfo project) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("projectId", project.projectId());
        map.put("name", project.name());
        map.put("path", project.path());
        map.put("files", project.files().stream().map(RendererFrames::fileEntry).toList());
        map.put("filesTruncated", project.filesTruncated());
        return map;
    }

    private static Map<String, Object> fileEntry(FileEntry entry) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("relativePath", entry.relativePath());
        map.put("directory", entry.directory());
        return map;
    }

    private static Map<String, Object> agentSummary(AgentConfig agent) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("agentId", agent.agentId());
        map.put("projectId", agent.projectId());
        map.put("name", agent.name());
        map.put("harnessId", agent.harnessId());
        map.put("providerId", agent.providerId());
        map.put("modelId", agent.modelId());
        map.put("authMethodId", agent.authMethodId());
        map.put("palette", agent.palette());
        map.put("hueShift", agent.hueShift());
        return map;
    }

    private static Map<String, Object> agentSnapshot(AgentConfig agent) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("agent", agentSummary(agent));
        map.put("status", STATUS_OFFLINE);
        map.put("sessionId", null);
        map.put("runId", null);
        map.put("lastSeq", 0L);
        map.put("toolCalls", List.of());
        map.put("pendingPermissions", List.of());
        return map;
    }
}
