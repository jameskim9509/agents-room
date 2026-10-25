package agentsroom.runtime.process;

import static org.assertj.core.api.Assertions.assertThat;

import agentsroom.runtime.testsupport.SpawningParent;
import agentsroom.runtime.testsupport.TestProcesses;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class ProcessTreeKillerTests {

    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    private Process parent;

    @AfterEach
    void cleanUp() {
        if (parent != null) {
            parent.toHandle().descendants().forEach(ProcessHandle::destroyForcibly);
            parent.destroyForcibly();
        }
    }

    @Test
    void killsTheProcessAndTheChildrenItStarted() throws Exception {
        parent = new ProcessBuilder(TestProcesses.javaCommand(SpawningParent.class))
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start();
        byte[] line = TestProcesses.readLineBytes(parent.getInputStream(), TIMEOUT);
        assertThat(new String(line, StandardCharsets.UTF_8).strip()).isEqualTo(SpawningParent.READY);
        List<ProcessHandle> children = parent.toHandle().descendants().toList();
        assertThat(children).isNotEmpty();

        boolean exited = ProcessTreeKiller.killTree(parent, TIMEOUT);

        assertThat(exited).isTrue();
        assertThat(parent.isAlive()).isFalse();
        assertThat(children).noneMatch(ProcessHandle::isAlive);
    }

    @Test
    void killDescendantsSparesTheRoot() throws Exception {
        parent = new ProcessBuilder(TestProcesses.javaCommand(SpawningParent.class))
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start();
        TestProcesses.readLineBytes(parent.getInputStream(), TIMEOUT);
        List<ProcessHandle> children = parent.toHandle().descendants().toList();
        assertThat(children).isNotEmpty();

        boolean exited = ProcessTreeKiller.killDescendants(parent.toHandle(), TIMEOUT);

        assertThat(exited).isTrue();
        assertThat(children).noneMatch(ProcessHandle::isAlive);
        assertThat(parent.isAlive()).isTrue();
    }
}
