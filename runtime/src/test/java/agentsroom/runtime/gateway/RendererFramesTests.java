package agentsroom.runtime.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import agentsroom.contract.AgentSummary;
import agentsroom.contract.Readiness;
import agentsroom.contract.RejectReason;
import agentsroom.contract.ServerMessage;
import agentsroom.runtime.agent.AgentConfig;
import agentsroom.runtime.persistence.RecentProject;
import agentsroom.runtime.project.FileEntry;
import agentsroom.runtime.project.ProjectErrorCode;
import agentsroom.runtime.project.ProjectInfo;
import agentsroom.runtime.testsupport.ContractSchema;
import agentsroom.runtime.testsupport.ContractSchema.Verdict;
import agentsroom.runtime.workspace.WorkspaceSnapshot;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The frames the runtime builds, checked against {@code core/asyncapi.yaml}. */
class RendererFramesTests {

    private static final String PROJECT_ID = UUID.randomUUID().toString();

    private final ContractMessageCodec codec = new ContractMessageCodec();

    /** Every frame type the runtime sends today, in every shape its data can give it. */
    @Test
    void everyFrameTheRuntimeBuildsMatchesTheContract() {
        ProjectInfo project = new ProjectInfo(PROJECT_ID, "demo", "C:\\work\\demo",
                List.of(new FileEntry(".git", true), new FileEntry("src", true), new FileEntry("src/App.java", false)),
                false);
        ProjectInfo empty = new ProjectInfo(PROJECT_ID, "C:\\", "C:\\", List.of(), true);
        List<RecentProject> recent = List.of(new RecentProject(PROJECT_ID, "demo", "C:\\work\\demo", 1L),
                new RecentProject(UUID.randomUUID().toString(), "notes", "D:\\notes", 0L));
        List<AgentConfig> agents = List.of(
                AgentConfig.seededCodex(PROJECT_ID),
                agent("codex", "openai", "chat-gpt", "gpt-5-codex", 5, 315),
                agent("codex", "amazon-bedrock", "aws-profile", null, 1, 45),
                agent("gemini-cli", "google", "oauth", null, 2, 0));

        List<ServerMessage> frames = new ArrayList<>();
        frames.add(RendererFrames.snapshot(new WorkspaceSnapshot(null, List.of(), null, List.of())));
        frames.add(RendererFrames.snapshot(new WorkspaceSnapshot(null, recent, null, List.of())));
        frames.add(RendererFrames.snapshot(new WorkspaceSnapshot(PROJECT_ID, recent, project, agents)));
        frames.add(RendererFrames.snapshot(new WorkspaceSnapshot(PROJECT_ID, recent, empty, List.of())));
        frames.add(RendererFrames.projectOpened(project));
        frames.add(RendererFrames.projectOpened(empty));
        for (ProjectErrorCode code : ProjectErrorCode.values()) {
            frames.add(RendererFrames.projectOpenFailed("relative\\path", code, "Path is not absolute"));
        }
        frames.add(RendererFrames.projectOpenFailed(null, ProjectErrorCode.PATH_NOT_ABSOLUTE, "Path is empty"));
        frames.add(RendererFrames.agentsUpdated(List.of()));
        frames.add(RendererFrames.agentsUpdated(agents));
        for (RejectReason reason : RejectReason.values()) {
            frames.add(RendererFrames.commandRejected(CommandEcho.of("startAgent"), reason, "Rejected"));
        }
        frames.add(RendererFrames.commandRejected(CommandEcho.of(CommandEcho.UNKNOWN_COMMAND),
                RejectReason.INVALID_MESSAGE, "Frame is not JSON"));
        frames.add(RendererFrames.commandRejected(new CommandEcho("saveAgentConfig", "a1", "p1", null),
                RejectReason.UNKNOWN_AGENT, "No agent a1 in the current project"));
        frames.add(RendererFrames.commandRejected(CommandEcho.permission("respondPermission", "p1"),
                RejectReason.NOT_IMPLEMENTED, "Not yet"));
        List<String> texts = frames.stream().map(codec::encode).toList();

        List<Verdict> verdicts = ContractSchema.validateServerMessages(texts);

        for (int i = 0; i < texts.size(); i++) {
            assertThat(verdicts.get(i).isAgentsRoomMessage()).as("%s %s", texts.get(i), verdicts.get(i).errors())
                    .isTrue();
        }
    }

    @Test
    void readinessAndReportsComeFromTheHarnessCatalog() {
        AgentSummary codex = summary(AgentConfig.seededCodex(PROJECT_ID));
        assertThat(codex.getReadiness()).isEqualTo(Readiness.CONFIGURED);
        assertThat(codex.getReportsToolDetails()).as("D21").isTrue();
        assertThat(codex.getReportsSubagentSessions()).as("D22").isFalse();

        AgentSummary codexOnBedrock = summary(agent("codex", "amazon-bedrock", "aws-profile", null, 0, 0));
        assertThat(codexOnBedrock.getReadiness()).as("not verified yet (ADR 0004)").isEqualTo(Readiness.UNSUPPORTED);
        assertThat(codexOnBedrock.getReportsToolDetails()).isTrue();

        AgentSummary unknown = summary(agent("gemini-cli", "google", "oauth", null, 0, 0));
        assertThat(unknown.getReadiness()).isEqualTo(Readiness.UNSUPPORTED);
        assertThat(unknown.getReportsToolDetails()).isFalse();
        assertThat(unknown.getReportsSubagentSessions()).isFalse();
    }

    @Test
    void omitsOptionalFieldsAndKeepsRequiredNulls() {
        String snapshot = codec.encode(RendererFrames.snapshot(new WorkspaceSnapshot(null, List.of(), null, List.of())));
        assertThat(snapshot).isEqualTo("{\"type\":\"snapshot\",\"currentProjectId\":null,\"recentProjects\":[],\"agents\":[]}");

        String rejected = codec.encode(RendererFrames.commandRejected(CommandEcho.agent("stopAgent", "a1"),
                RejectReason.UNKNOWN_AGENT, "No agent a1"));
        assertThat(rejected).isEqualTo("{\"type\":\"commandRejected\",\"command\":\"stopAgent\","
                + "\"reason\":\"UNKNOWN_AGENT\",\"message\":\"No agent a1\",\"agentId\":\"a1\"}");
    }

    @Test
    void masksDiagnosticMessages() {
        String key = "sk-" + "abcdefghijklmnop";

        String rejected = codec.encode(RendererFrames.commandRejected(CommandEcho.of("sendPrompt"),
                RejectReason.INVALID_MESSAGE, "saw " + key));
        String failed = codec.encode(RendererFrames.projectOpenFailed("C:\\x", ProjectErrorCode.PATH_NOT_FOUND,
                "saw " + key));

        assertThat(rejected).doesNotContain(key).contains("saw ****");
        assertThat(failed).doesNotContain(key).contains("saw ****");
    }

    private static AgentSummary summary(AgentConfig agent) {
        return RendererFrames.agentsUpdated(List.of(agent)).getAgents().getFirst();
    }

    private static AgentConfig agent(
            String harnessId, String providerId, String authMethodId, String modelId, int palette, int hueShift) {
        return new AgentConfig(UUID.randomUUID().toString(), PROJECT_ID, harnessId, harnessId, providerId, modelId,
                authMethodId, palette, hueShift);
    }
}
