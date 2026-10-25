package agentsroom.runtime.workspace;

import agentsroom.runtime.agent.AgentConfig;
import agentsroom.runtime.persistence.AgentConfigRepository;
import agentsroom.runtime.persistence.RecentProject;
import agentsroom.runtime.persistence.RecentProjectsRepository;
import agentsroom.runtime.project.FileTree;
import agentsroom.runtime.project.FileTreeReader;
import agentsroom.runtime.project.ProjectInfo;
import agentsroom.runtime.project.ProjectPathException;
import agentsroom.runtime.project.ProjectPathValidator;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * The Project/Workspace service (architecture.md): opens projects, keeps the recent list and the
 * project's agents, and holds the single active project (D17).
 *
 * <p>Opening takes two steps. {@link #prepareOpen} does the slow part (validation, file tree) without
 * a lock and changes nothing; {@link #open} then makes the project current, quickly, so that the
 * gateway can run it together with its broadcast.
 *
 * <p>{@code projectId} stays stable for a real path only while {@code recent-projects.json} holds it.
 * So an {@code agents.json} is written only for a project that file already holds on disk, and while
 * the file cannot be read, nothing is written: projects then open for this session only, and every
 * open tries the file again.
 */
@Service
public class WorkspaceService {

    /** How many recent projects the snapshot shows. All of them stay stored, to keep ids stable. */
    static final int RECENT_PROJECTS_SHOWN = 10;

    private static final Logger log = LoggerFactory.getLogger(WorkspaceService.class);

    private final ProjectPathValidator validator;
    private final FileTreeReader treeReader;
    private final RecentProjectsRepository recentProjectsRepository;
    private final AgentConfigRepository agentConfigRepository;
    private final Clock clock;

    /** Most recent first. Guarded by {@code this}. */
    private List<RecentProject> recentProjects = new ArrayList<>();

    /** Whether {@code recent-projects.json} has been read (or found missing). Guarded by {@code this}. */
    private boolean recentProjectsLoaded;

    /** The projectIds {@code recent-projects.json} holds on disk. Guarded by {@code this}. */
    private Set<String> storedProjectIds = Set.of();

    /** Seeded agents waiting until their project is stored, by projectId. Guarded by {@code this}. */
    private final Map<String, List<AgentConfig>> unsavedAgents = new HashMap<>();

    /** Guarded by {@code this}. {@code null} while no project is open. */
    private CurrentProject current;

    WorkspaceService(
            ProjectPathValidator validator,
            FileTreeReader treeReader,
            RecentProjectsRepository recentProjectsRepository,
            AgentConfigRepository agentConfigRepository,
            Clock clock) {
        this.validator = validator;
        this.treeReader = treeReader;
        this.recentProjectsRepository = recentProjectsRepository;
        this.agentConfigRepository = agentConfigRepository;
        this.clock = clock;
        loadRecentProjects();
    }

    /**
     * Step one: validates {@code rawPath} and reads its file tree. Changes nothing and takes no lock,
     * because this is the slow part (disk) and a connecting renderer must not wait for it.
     */
    public ProjectOpening prepareOpen(String rawPath) {
        Path realPath;
        try {
            realPath = validator.validate(rawPath);
        } catch (ProjectPathException e) {
            log.info("Project path refused: {}", e.code());
            return new ProjectOpening.Refused(rawPath, e.code(), e.getMessage());
        }
        return new ProjectOpening.Ready(realPath, treeReader.read(realPath));
    }

    /**
     * Step two: makes a checked folder the current project. Issues or reuses its {@code projectId},
     * moves it to the top of the recent list and loads its agents, seeding one on the first open.
     * Only small files are read and written here.
     */
    public synchronized OpenedProject open(ProjectOpening.Ready ready) {
        if (!recentProjectsLoaded) {
            loadRecentProjects();
        }
        RecentProject recent = recordOpened(ready.realPath());
        List<AgentConfig> agents = agentsOf(recent.projectId());
        saveUnsavedAgents();

        FileTree tree = ready.tree();
        ProjectInfo project = new ProjectInfo(recent.projectId(), recent.name(), recent.path(), tree.entries(),
                tree.truncated());
        current = new CurrentProject(project, agents);
        log.info("Opened project {} ({} entries{}, {} agents{})", project.projectId(), tree.entries().size(),
                tree.truncated() ? ", truncated" : "", agents.size(),
                storedProjectIds.contains(project.projectId()) ? "" : ", not stored yet");
        return new OpenedProject(project, agents);
    }

    /**
     * Whether {@code projectId} names a project this runtime knows: one on the recent list, stored or
     * issued for this session only. A command naming any other projectId is {@code UNKNOWN_PROJECT}.
     */
    public synchronized boolean isKnownProject(String projectId) {
        return recentProjects.stream().anyMatch(project -> project.projectId().equals(projectId));
    }

    /**
     * The current project's agent with this id. The runtime holds the current project's agents only,
     * so for a command an agent of another project is as unknown as a made-up id.
     */
    public synchronized Optional<AgentConfig> findAgent(String agentId) {
        if (current == null) {
            return Optional.empty();
        }
        return current.agents().stream().filter(agent -> agent.agentId().equals(agentId)).findFirst();
    }

    /** The current state, for the snapshot a renderer gets on every connect. In memory only. */
    public synchronized WorkspaceSnapshot snapshot() {
        List<RecentProject> shown = recentProjects.subList(0, Math.min(RECENT_PROJECTS_SHOWN, recentProjects.size()));
        if (current == null) {
            return new WorkspaceSnapshot(null, shown, null, List.of());
        }
        return new WorkspaceSnapshot(current.project().projectId(), shown, current.project(), current.agents());
    }

    /**
     * Reads {@code recent-projects.json}. When it reads, the stored ids win over ids issued while it
     * could not be read, so a folder gets back the id it had before.
     */
    private void loadRecentProjects() {
        List<RecentProject> stored;
        try {
            stored = recentProjectsRepository.load();
        } catch (UncheckedIOException e) {
            log.error("Could not read the recent projects; projects open for this session only until it reads",
                    e);
            return;
        }
        List<RecentProject> merged = new ArrayList<>();
        for (RecentProject opened : recentProjects) {
            if (stored.stream().anyMatch(project -> samePath(project, opened))) {
                unsavedAgents.remove(opened.projectId());
            } else {
                merged.add(opened);
            }
        }
        merged.addAll(stored);
        recentProjects = merged;
        storedProjectIds = ids(stored);
        recentProjectsLoaded = true;
    }

    private RecentProject recordOpened(Path realPath) {
        Optional<RecentProject> known = recentProjects.stream()
                .filter(project -> Path.of(project.path()).equals(realPath))
                .findFirst();
        String projectId = known.map(RecentProject::projectId).orElseGet(() -> UUID.randomUUID().toString());
        RecentProject opened = new RecentProject(projectId, projectName(realPath), realPath.toString(), clock.millis());
        known.ifPresent(recentProjects::remove);
        recentProjects.addFirst(opened);
        if (recentProjectsLoaded && recentProjectsRepository.save(List.copyOf(recentProjects))) {
            storedProjectIds = ids(recentProjects);
        }
        return opened;
    }

    private List<AgentConfig> agentsOf(String projectId) {
        List<AgentConfig> unsaved = unsavedAgents.get(projectId);
        if (unsaved != null) {
            return unsaved;
        }
        if (storedProjectIds.contains(projectId)) {
            Optional<List<AgentConfig>> stored;
            try {
                stored = agentConfigRepository.load(projectId);
            } catch (UncheckedIOException e) {
                // The file exists but cannot be read (locked?). Work with a default agent for this open
                // and leave the file alone, so the stored agentId is not lost; the next open reads again.
                log.error("Could not read the agents of project {}; using a default agent for now", projectId, e);
                return List.of(AgentConfig.seededCodex(projectId));
            }
            if (stored.isPresent()) {
                return stored.get();
            }
        }
        List<AgentConfig> seeded = List.of(AgentConfig.seededCodex(projectId));
        unsavedAgents.put(projectId, seeded);
        log.info("Seeded the default agent for project {}", projectId);
        return seeded;
    }

    /** Writes seeded agents whose project is stored now. Until then they live in memory. */
    private void saveUnsavedAgents() {
        unsavedAgents.entrySet().removeIf(entry -> storedProjectIds.contains(entry.getKey())
                && agentConfigRepository.save(entry.getKey(), entry.getValue()));
    }

    private static boolean samePath(RecentProject a, RecentProject b) {
        return Path.of(a.path()).equals(Path.of(b.path()));
    }

    private static Set<String> ids(List<RecentProject> projects) {
        return projects.stream().map(RecentProject::projectId).collect(Collectors.toUnmodifiableSet());
    }

    private static String projectName(Path realPath) {
        Path fileName = realPath.getFileName();
        // A drive or share root has no file name; show the root itself.
        return fileName != null ? fileName.toString() : realPath.toString();
    }

    private record CurrentProject(ProjectInfo project, List<AgentConfig> agents) {

        CurrentProject {
            agents = List.copyOf(agents);
        }
    }
}
