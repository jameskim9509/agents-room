package agentsroom.runtime.workspace;

import agentsroom.runtime.agent.AgentConfig;
import agentsroom.runtime.persistence.RecentProject;
import agentsroom.runtime.project.ProjectInfo;
import java.util.List;

/**
 * The data behind the renderer's snapshot frame (ADR 0005 decision 2, D4). Building the wire frame
 * from it is the gateway's job.
 *
 * @param currentProjectId {@code null} while no project is open
 * @param recentProjects most recent first
 * @param project the current project, {@code null} while none is open
 * @param agents the current project's agents, empty while none is open
 */
public record WorkspaceSnapshot(
        String currentProjectId, List<RecentProject> recentProjects, ProjectInfo project, List<AgentConfig> agents) {

    public WorkspaceSnapshot {
        recentProjects = List.copyOf(recentProjects);
        agents = List.copyOf(agents);
    }
}
