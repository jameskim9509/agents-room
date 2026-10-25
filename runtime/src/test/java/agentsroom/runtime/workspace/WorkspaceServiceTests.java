package agentsroom.runtime.workspace;

import static org.assertj.core.api.Assertions.assertThat;

import agentsroom.runtime.agent.AgentConfig;
import agentsroom.runtime.persistence.AgentConfigRepository;
import agentsroom.runtime.persistence.JsonFileStore;
import agentsroom.runtime.persistence.RecentProject;
import agentsroom.runtime.persistence.RecentProjectsRepository;
import agentsroom.runtime.persistence.RuntimeDataDirectory;
import agentsroom.runtime.project.FileEntry;
import agentsroom.runtime.project.FileTreeReader;
import agentsroom.runtime.project.ProjectErrorCode;
import agentsroom.runtime.project.ProjectPathValidator;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorkspaceServiceTests {

    @TempDir
    Path dataDir;

    @TempDir
    Path projects;

    private Instant now = Instant.parse("2026-10-25T00:00:00Z");

    @Test
    void opensAValidProjectAndSeedsTheDefaultCodexAgent() throws IOException {
        Path folder = projectFolder("alpha");
        Files.writeString(folder.resolve("README.md"), "hi", StandardCharsets.UTF_8);

        OpenedProject opened = open(newService(), folder.toString());

        assertThat(opened.project().name()).isEqualTo("alpha");
        assertThat(opened.project().path()).isEqualTo(folder.toRealPath().toString());
        assertThat(UUID.fromString(opened.project().projectId())).isNotNull();
        assertThat(opened.project().files()).containsExactly(new FileEntry("README.md", false));
        assertThat(opened.agents()).singleElement().satisfies(agent -> {
            assertThat(UUID.fromString(agent.agentId())).isNotNull();
            assertThat(agent.projectId()).isEqualTo(opened.project().projectId());
            assertThat(agent.name()).isEqualTo("Codex");
            assertThat(agent.harnessId()).isEqualTo("codex");
            assertThat(agent.providerId()).isEqualTo("openai");
            assertThat(agent.modelId()).isNull();
            assertThat(agent.authMethodId()).isEqualTo("chat-gpt");
            assertThat(agent.palette()).isZero();
            assertThat(agent.hueShift()).isZero();
        });
        assertThat(agentsFile(opened.project().projectId())).exists();
    }

    @Test
    void projectIdAndSeededAgentSurviveARestart() throws IOException {
        Path folder = projectFolder("alpha");
        OpenedProject first = open(newService(), folder.toString());

        OpenedProject afterRestart = open(newService(), folder.toString());

        assertThat(afterRestart.project().projectId()).isEqualTo(first.project().projectId());
        assertThat(afterRestart.agents()).isEqualTo(first.agents());
    }

    @Test
    void theSameFolderByAnotherSpellingIsTheSameProject() throws IOException {
        Path folder = projectFolder("alpha");
        WorkspaceService service = newService();
        String id = open(service, folder.toString()).project().projectId();

        String otherSpelling = folder.resolve("..").resolve("alpha").toString();

        String again = open(service, otherSpelling).project().projectId();

        assertThat(again).isEqualTo(id);
        assertThat(service.snapshot().recentProjects()).hasSize(1);
    }

    @Test
    void keepsRecentProjectsMostRecentFirstAcrossRestarts() throws IOException {
        Path alpha = projectFolder("alpha");
        Path beta = projectFolder("beta");
        WorkspaceService service = newService();
        open(service, alpha.toString());
        now = now.plusSeconds(60);
        open(service, beta.toString());
        now = now.plusSeconds(60);
        open(service, alpha.toString());

        List<RecentProject> recent = newService().snapshot().recentProjects();

        assertThat(recent).extracting(RecentProject::name).containsExactly("alpha", "beta");
        assertThat(recent.getFirst().lastOpenedAt()).isEqualTo(now.toEpochMilli());
    }

    @Test
    void oneActiveProjectAtATime() throws IOException {
        WorkspaceService service = newService();
        open(service, projectFolder("alpha").toString());

        OpenedProject beta = open(service, projectFolder("beta").toString());

        WorkspaceSnapshot snapshot = service.snapshot();
        assertThat(snapshot.currentProjectId()).isEqualTo(beta.project().projectId());
        assertThat(snapshot.project()).isEqualTo(beta.project());
        assertThat(snapshot.agents()).isEqualTo(beta.agents());
    }

    @Test
    void aFailedOpenChangesNothing() throws IOException {
        WorkspaceService service = newService();
        OpenedProject alpha = open(service, projectFolder("alpha").toString());
        WorkspaceSnapshot before = service.snapshot();

        ProjectOpening result = service.prepareOpen(projects.resolve("missing").toString());

        assertThat(result).isInstanceOfSatisfying(ProjectOpening.Refused.class, refused -> {
            assertThat(refused.code()).isEqualTo(ProjectErrorCode.PATH_NOT_FOUND);
            assertThat(refused.path()).isEqualTo(projects.resolve("missing").toString());
        });
        assertThat(service.snapshot()).isEqualTo(before);
        assertThat(service.snapshot().currentProjectId()).isEqualTo(alpha.project().projectId());
    }

    @Test
    void snapshotBeforeAnyProjectIsEmpty() {
        WorkspaceSnapshot snapshot = newService().snapshot();

        assertThat(snapshot.currentProjectId()).isNull();
        assertThat(snapshot.project()).isNull();
        assertThat(snapshot.agents()).isEmpty();
        assertThat(snapshot.recentProjects()).isEmpty();
    }

    @Test
    void aCorruptAgentsFileIsKeptAsideAndTheAgentIsSeededAgain() throws IOException {
        Path folder = projectFolder("alpha");
        OpenedProject first = open(newService(), folder.toString());
        Path agentsFile = agentsFile(first.project().projectId());
        String corrupt = "{\"version\":1,\"agents\":[{\"agentId\":\"not-a-uuid\"}]}";
        Files.writeString(agentsFile, corrupt, StandardCharsets.UTF_8);

        OpenedProject second = open(newService(), folder.toString());

        assertThat(second.agents()).singleElement().extracting(AgentConfig::agentId)
                .isNotEqualTo(first.agents().getFirst().agentId());
        assertThat(siblingsOf(agentsFile)).anyMatch(name -> name.startsWith("agents.json.corrupt-"));
    }

    @Test
    void aCorruptRecentProjectsFileIsKeptAsideAndTheListStartsEmpty() throws IOException {
        Path recentFile = RuntimeDataDirectory.at(dataDir).recentProjectsFile();
        Files.writeString(recentFile, "[garbage", StandardCharsets.UTF_8);

        WorkspaceService service = newService();

        assertThat(service.snapshot().recentProjects()).isEmpty();
        assertThat(siblingsOf(recentFile)).anyMatch(name -> name.startsWith("recent-projects.json.corrupt-"));
        open(service, projectFolder("alpha").toString());
        assertThat(newService().snapshot().recentProjects()).extracting(RecentProject::name).containsExactly("alpha");
    }

    @Test
    void whileRecentProjectsCannotBeReadNothingIsWrittenAndTheStoredIdsComeBack() throws IOException {
        Path folder = projectFolder("alpha");
        OpenedProject stored = open(newService(), folder.toString());
        Path recentFile = RuntimeDataDirectory.at(dataDir).recentProjectsFile();
        byte[] content = Files.readAllBytes(recentFile);
        Files.delete(recentFile);
        block(recentFile);
        WorkspaceService service = newService();

        OpenedProject sessionOnly = open(service, folder.toString());

        assertThat(sessionOnly.project().projectId()).isNotEqualTo(stored.project().projectId());
        assertThat(projectDirectories()).containsExactly(stored.project().projectId());
        assertThat(open(service, folder.toString())).isEqualTo(sessionOnly);

        unblock(recentFile);
        Files.write(recentFile, content);
        OpenedProject readAgain = open(service, folder.toString());

        assertThat(readAgain.project().projectId()).isEqualTo(stored.project().projectId());
        assertThat(readAgain.agents()).isEqualTo(stored.agents());
        assertThat(service.snapshot().recentProjects()).extracting(RecentProject::projectId)
                .containsExactly(stored.project().projectId());
        assertThat(projectDirectories()).containsExactly(stored.project().projectId());
    }

    @Test
    void aProjectWhoseIdCouldNotBeStoredKeepsItsAgentInMemoryUntilItIs() throws IOException {
        WorkspaceService service = newService();
        Path recentFile = RuntimeDataDirectory.at(dataDir).recentProjectsFile();
        block(recentFile);
        Path beta = projectFolder("beta");

        OpenedProject unsaved = open(service, beta.toString());

        assertThat(projectDirectories()).as("no agents.json for an id that is not stored").isEmpty();
        assertThat(open(service, beta.toString())).isEqualTo(unsaved);

        unblock(recentFile);
        open(service, projectFolder("gamma").toString());
        OpenedProject afterRestart = open(newService(), beta.toString());

        assertThat(afterRestart.project().projectId()).isEqualTo(unsaved.project().projectId());
        assertThat(afterRestart.agents()).isEqualTo(unsaved.agents());
    }

    private WorkspaceService newService() {
        Clock clock = new Clock() {
            @Override
            public ZoneOffset getZone() {
                return ZoneOffset.UTC;
            }

            @Override
            public Clock withZone(ZoneId zone) {
                return this;
            }

            @Override
            public Instant instant() {
                return now;
            }
        };
        RuntimeDataDirectory directory = RuntimeDataDirectory.at(dataDir);
        JsonFileStore store = new JsonFileStore(clock);
        return new WorkspaceService(new ProjectPathValidator(), new FileTreeReader(),
                new RecentProjectsRepository(store, directory), new AgentConfigRepository(store, directory), clock);
    }

    private Path projectFolder(String name) throws IOException {
        return Files.createDirectories(projects.resolve(name));
    }

    private Path agentsFile(String projectId) {
        return RuntimeDataDirectory.at(dataDir).agentsFile(projectId);
    }

    /** Both steps, as the gateway runs them. */
    private static OpenedProject open(WorkspaceService service, String path) {
        ProjectOpening opening = service.prepareOpen(path);
        assertThat(opening).isInstanceOf(ProjectOpening.Ready.class);
        return service.open((ProjectOpening.Ready) opening);
    }

    private static List<String> siblingsOf(Path file) throws IOException {
        try (Stream<Path> files = Files.list(file.getParent())) {
            return files.map(path -> path.getFileName().toString()).toList();
        }
    }

    /** The directories under {@code projects/}: one per project that has an {@code agents.json}. */
    private List<String> projectDirectories() throws IOException {
        Path root = RuntimeDataDirectory.at(dataDir).root().resolve("projects");
        if (!Files.exists(root)) {
            return List.of();
        }
        try (Stream<Path> directories = Files.list(root)) {
            return directories.map(path -> path.getFileName().toString()).toList();
        }
    }

    /** Puts a non-empty directory where {@code file} belongs: it can then be neither read nor replaced. */
    private static void block(Path file) throws IOException {
        Files.createDirectories(file.resolve("blocker"));
    }

    private static void unblock(Path file) throws IOException {
        Files.delete(file.resolve("blocker"));
        Files.delete(file);
    }
}
