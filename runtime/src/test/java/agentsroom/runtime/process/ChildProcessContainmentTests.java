package agentsroom.runtime.process;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import agentsroom.runtime.testsupport.ContainedParent;
import agentsroom.runtime.testsupport.TestProcesses;
import agentsroom.runtime.testsupport.TestProcesses.StreamCollector;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.slf4j.LoggerFactory;

/**
 * The runtime's children die with the runtime even when it is killed and runs no code (SCRUM-22).
 * {@link ContainedParent} stands in for the runtime; it starts a child that has a child of its own.
 */
@EnabledOnOs(OS.WINDOWS)
class ChildProcessContainmentTests {

    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    /**
     * Spawns the command in its arguments the way an Electron main process spawns the runtime: piped
     * stdio and not detached, so libuv puts it into its own kill-on-close job.
     */
    private static final String NODE_SHELL = """
            const { spawn } = require('node:child_process');
            const [command, ...args] = process.argv.slice(1);
            const runtime = spawn(command, args, { stdio: ['pipe', 'pipe', 'inherit'] });
            runtime.stdout.pipe(process.stdout);
            setInterval(() => {}, 60000);
            """;

    private final List<ProcessHandle> started = new ArrayList<>();

    @AfterEach
    void killLeftovers() {
        for (ProcessHandle process : started) {
            process.descendants().forEach(ProcessHandle::destroyForcibly);
            process.destroyForcibly();
        }
    }

    /** Repeated: a child that starts its own child at once must never get out of the job. */
    @RepeatedTest(3)
    void theChildTreeDiesWhenTheRuntimeIsKilledWithoutRunningAnyCode() throws Exception {
        Process runtime = start(runtimeCommand());
        Tree tree = readTree(runtime.getInputStream());

        // TerminateProcess: no shutdown hook, no ProcessTreeKiller.
        runtime.destroyForcibly();

        assertDies(tree.child(), tree.grandchild());
    }

    @Test
    void aGrandchildWhoseParentExitedStillDiesWithTheRuntime() throws Exception {
        Process runtime = start(runtimeCommand());
        Tree tree = readTree(runtime.getInputStream());
        tree.child().destroyForcibly();
        assertThat(tree.child().onExit()).succeedsWithin(TIMEOUT);
        assertThat(runtime.toHandle().descendants()).as("orphaned, so not a descendant").doesNotContain(tree.grandchild());
        assertThat(tree.grandchild().isAlive()).isTrue();

        runtime.destroyForcibly();

        assertDies(tree.grandchild());
    }

    @Test
    void aNodeChildsOwnChildDiesWithTheRuntime() throws Exception {
        // libuv in the Node child puts the grandchild into a job of its own that allows silent breakaway.
        Process runtime = start(requiringNode(runtimeCommand(ContainedParent.NODE_CHILD)));
        Tree tree = readTree(runtime.getInputStream());

        runtime.destroyForcibly();

        assertDies(tree.child(), tree.grandchild());
    }

    @Test
    void theChildTreeDiesWhenANodeShellIsKilledWithoutATreeKill() throws Exception {
        Process shell = start(requiringNode(nodeShell(runtimeCommand())));
        StreamCollector stderr = TestProcesses.collect(shell.getErrorStream());
        Tree tree = readTree(shell.getInputStream());

        // Like "taskkill /F /PID <shell>" without /T, or End task on the shell: libuv's job then kills
        // the runtime at once, and only the runtime's own job can take its children with it.
        shell.destroyForcibly();

        assertThat(tree.runtime().onExit()).as("runtime, stderr: %s", stderr.text()).succeedsWithin(TIMEOUT);
        assertDies(tree.child(), tree.grandchild());
    }

    private static void assertDies(ProcessHandle... processes) {
        for (ProcessHandle process : processes) {
            assertThat(process.onExit()).as("exit of process %s", process.pid()).succeedsWithin(TIMEOUT);
        }
    }

    private static List<String> runtimeCommand(String... args) {
        List<String> command = new ArrayList<>(List.of(
                TestProcesses.javaExecutable(),
                "--enable-native-access=ALL-UNNAMED",
                "-cp",
                TestProcesses.classpathOf(ContainedParent.class, ChildProcessContainment.class, LoggerFactory.class),
                ContainedParent.class.getName()));
        command.addAll(List.of(args));
        return command;
    }

    private static List<String> nodeShell(List<String> runtimeCommand) {
        List<String> command = new ArrayList<>(List.of("node", "-e", NODE_SHELL));
        command.addAll(runtimeCommand);
        return command;
    }

    /** Skips the test when node is not on the PATH. */
    private static List<String> requiringNode(List<String> command) {
        try {
            Process version = new ProcessBuilder("node", "--version").redirectErrorStream(true).start();
            version.getInputStream().readAllBytes();
            assumeTrue(version.waitFor() == 0, "node --version failed");
        } catch (IOException e) {
            assumeTrue(false, "node is not on the PATH: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            assumeTrue(false, "interrupted");
        }
        return command;
    }

    private Process start(List<String> command) throws IOException {
        Process process = new ProcessBuilder(command).start();
        started.add(process.toHandle());
        return process;
    }

    private Tree readTree(InputStream stdout) throws Exception {
        while (true) {
            String line = new String(TestProcesses.readLineBytes(stdout, TIMEOUT), StandardCharsets.UTF_8).strip();
            assertThat(line).as("ContainedParent output").isNotEmpty();
            if (!line.startsWith(ContainedParent.PREFIX)) {
                continue;
            }
            List<ProcessHandle> processes = Arrays.stream(line.substring(ContainedParent.PREFIX.length()).split(" "))
                    .map(pid -> ProcessHandle.of(Long.parseLong(pid)).orElseThrow())
                    .toList();
            started.addAll(processes);
            assertThat(processes).allMatch(ProcessHandle::isAlive);
            return new Tree(processes.get(0), processes.get(1), processes.get(2));
        }
    }

    private record Tree(ProcessHandle runtime, ProcessHandle child, ProcessHandle grandchild) {
    }
}
