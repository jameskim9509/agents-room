package agentsroom.runtime.persistence;

import agentsroom.runtime.agent.AgentConfig;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Repository;

/** {@code projects/<projectId>/agents.json}: one project's AgentConfigs. */
@Repository
public class AgentConfigRepository {

    static final int FORMAT_VERSION = 1;

    private static final Logger log = LoggerFactory.getLogger(AgentConfigRepository.class);

    private final JsonFileStore store;
    private final RuntimeDataDirectory dataDirectory;

    public AgentConfigRepository(JsonFileStore store, RuntimeDataDirectory dataDirectory) {
        this.store = store;
        this.dataDirectory = dataDirectory;
    }

    /**
     * @return the stored agents; empty when the file is missing or was corrupt (moved aside)
     * @throws UncheckedIOException when the file exists but cannot be read; then it must not be
     *     overwritten
     */
    public Optional<List<AgentConfig>> load(String projectId) {
        return store.read(dataDirectory.agentsFile(projectId), AgentsDocument.class,
                        document -> isValid(document, projectId))
                .map(AgentsDocument::agents);
    }

    /**
     * Saves the agents. A failed write is logged, not thrown: the agents still work for this session.
     *
     * @return whether the agents reached the disk
     */
    public boolean save(String projectId, List<AgentConfig> agents) {
        try {
            store.write(dataDirectory.agentsFile(projectId), new AgentsDocument(FORMAT_VERSION, agents));
            return true;
        } catch (UncheckedIOException e) {
            log.error("Could not save the agents of project {}", projectId, e);
            return false;
        }
    }

    private static boolean isValid(AgentsDocument document, String projectId) {
        return document.version() == FORMAT_VERSION
                && document.agents() != null
                && document.agents().stream().allMatch(agent -> isValid(agent, projectId));
    }

    private static boolean isValid(AgentConfig agent, String projectId) {
        return agent != null
                && RuntimeDataDirectory.isCanonicalUuid(agent.agentId())
                && projectId.equals(agent.projectId())
                && isPresent(agent.name())
                && isPresent(agent.harnessId())
                && isPresent(agent.providerId())
                && isPresent(agent.authMethodId())
                && agent.palette() >= 0
                && agent.palette() < AgentConfig.PALETTE_COUNT;
    }

    private static boolean isPresent(String value) {
        return value != null && !value.isBlank();
    }

    /** The file format. {@code version} lets a later runtime migrate it. */
    record AgentsDocument(int version, List<AgentConfig> agents) {
    }
}
