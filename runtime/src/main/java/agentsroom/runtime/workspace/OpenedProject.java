package agentsroom.runtime.workspace;

import agentsroom.runtime.agent.AgentConfig;
import agentsroom.runtime.project.ProjectInfo;
import java.util.List;

/** The project {@link WorkspaceService#open} made current, with its agents. */
public record OpenedProject(ProjectInfo project, List<AgentConfig> agents) {

    public OpenedProject {
        agents = List.copyOf(agents);
    }
}
