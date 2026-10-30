package agentsroom.runtime.process;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import agentsroom.runtime.testsupport.ContainedParent;
import agentsroom.runtime.testsupport.ReleasingParent;
import agentsroom.runtime.testsupport.TestProcesses;
import agentsroom.runtime.testsupport.TestProcesses.StreamCollector;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
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
 *
 * <p>The exception is a sign-in (SCRUM-65): what an adapter's tree starts while it signs in (the browser
 * of the login page) leaves the jobs and outlives the runtime, while every other process stays
 * contained. {@link ReleasingParent} stands in for the runtime there, Node workers for the adapters, and
 * a detached child of a worker for the browser (the way PowerShell's {@code Start} launches one).
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

    // ── Sign-ins (SCRUM-65) ───────────────────────────────────────────

    @Test
    void aSignInReleasesOnlyItsOwnAdaptersNewProcessesAndOnlyWhileItLasts() throws Exception {
        Releasing runtime = startReleasing();
        ProcessHandle signingIn = runtime.start("a");
        ProcessHandle other = runtime.start("b");
        runtime.ask("release a");
        ProcessHandle browser = runtime.spawn("a", "detached");
        ProcessHandle othersDetached = runtime.spawn("b", "detached");
        ProcessHandle othersAttached = runtime.spawn("b", "attached");
        runtime.ask("end a");
        ProcessHandle afterTheSignIn = runtime.spawn("a", "detached");

        runtime.process().destroyForcibly();

        assertDies(signingIn, other, othersDetached, othersAttached, afterTheSignIn);
        assertThat(browser.isAlive()).as("the sign-in's browser outlives the runtime").isTrue();
    }

    @Test
    void theRuntimesJobLetsProcessesGoUntilTheLastOfSeveralSignInsEnds() throws Exception {
        Releasing runtime = startReleasing();
        ProcessHandle first = runtime.start("a");
        ProcessHandle second = runtime.start("b");
        runtime.ask("release a");
        runtime.ask("release b");
        runtime.ask("end a");
        ProcessHandle secondsBrowser = runtime.spawn("b", "detached");
        ProcessHandle firstAfterItsSignIn = runtime.spawn("a", "detached");
        runtime.ask("end b");
        ProcessHandle secondAfterItsSignIn = runtime.spawn("b", "detached");

        runtime.process().destroyForcibly();

        assertDies(first, second, firstAfterItsSignIn, secondAfterItsSignIn);
        assertThat(secondsBrowser.isAlive()).as("b still signed in when it started").isTrue();
    }

    @Test
    void anAdapterStartedDuringAnotherSignInIsContainedWithItsChildren() throws Exception {
        Releasing runtime = startReleasing();
        ProcessHandle signingIn = runtime.start("a");
        runtime.ask("release a");
        // Created while the runtime's job lets new processes go: adopting it brings it back.
        ProcessHandle late = runtime.start("c");
        ProcessHandle attached = runtime.spawn("c", "attached");
        ProcessHandle detached = runtime.spawn("c", "detached");

        runtime.process().destroyForcibly();

        assertDies(signingIn, late, attached, detached);
    }

    @Test
    void theRuntimesCleanupOnExitSparesWhatASignInReleased() throws Exception {
        Releasing runtime = startReleasing();
        ProcessHandle adapter = runtime.start("a");
        runtime.ask("release a");
        ProcessHandle browser = runtime.spawn("a", "detached");
        runtime.ask("end a");
        ProcessHandle child = runtime.spawn("a", "detached");
        // Its parent (the worker) still runs, so the browser is one of the runtime's descendants.
        assertThat(runtime.process().toHandle().descendants()).contains(adapter, browser, child);

        // What the orderly shutdown (ChildProcessReaper) and the halt after its grace period do.
        runtime.ask("reap");

        assertDies(adapter, child);
        assertThat(browser.isAlive()).as("the sign-in's browser outlives the cleanup").isTrue();
    }

    /**
     * A stop, a kill or an unexpected exit closes the adapter's job while its authenticate call still
     * waits; the call's own release closes afterwards. The sign-in ends once, so another adapter's sign-in
     * in progress keeps its count, and once that one ended too, nothing gets out, not even a process the
     * runtime itself starts.
     */
    @Test
    void anAdapterJobClosedMidSignInEndsItsReleaseOnceAndEverythingIsContainedAgain() throws Exception {
        Releasing runtime = startReleasing();
        ProcessHandle stopped = runtime.start("a");
        ProcessHandle other = runtime.start("b");
        runtime.ask("release a");
        runtime.ask("release b");
        runtime.ask("close a");
        runtime.ask("end a");
        ProcessHandle othersBrowser = runtime.spawn("b", "detached");
        runtime.ask("end b");
        ProcessHandle stoppedsAfterwards = runtime.spawn("a", "detached");
        ProcessHandle othersAfterwards = runtime.spawn("b", "detached");
        ProcessHandle runtimesOwn = runtime.track(runtime.ask("direct"));

        runtime.process().destroyForcibly();

        assertDies(stopped, other, stoppedsAfterwards, othersAfterwards, runtimesOwn);
        assertThat(othersBrowser.isAlive()).as("b still signed in when it started").isTrue();
    }

    private Releasing startReleasing() throws IOException {
        List<String> command = List.of(
                TestProcesses.javaExecutable(),
                "--enable-native-access=ALL-UNNAMED",
                "-cp",
                TestProcesses.classpathOf(ReleasingParent.class, ChildProcessContainment.class, LoggerFactory.class),
                ReleasingParent.class.getName());
        Process process = new ProcessBuilder(requiringNode(command)).redirectError(ProcessBuilder.Redirect.DISCARD)
                .start();
        started.add(process.toHandle());
        return new Releasing(process);
    }

    /** {@link ReleasingParent} at the other end of its stdin and stdout. */
    private final class Releasing {

        private final Process process;
        private final OutputStream in;
        private final InputStream out;

        Releasing(Process process) {
            this.process = process;
            this.in = process.getOutputStream();
            this.out = process.getInputStream();
        }

        Process process() {
            return process;
        }

        String ask(String command) throws Exception {
            in.write((command + "\n").getBytes(StandardCharsets.UTF_8));
            in.flush();
            String answer = new String(TestProcesses.readLineBytes(out, TIMEOUT), StandardCharsets.UTF_8).strip();
            assertThat(answer).as("answer to %s", command).isNotEmpty();
            return answer;
        }

        ProcessHandle start(String worker) throws Exception {
            return track(ask("start " + worker));
        }

        ProcessHandle spawn(String worker, String how) throws Exception {
            return track(ask("spawn " + worker + " " + how));
        }

        ProcessHandle track(String pid) {
            ProcessHandle process = ProcessHandle.of(Long.parseLong(pid)).orElseThrow();
            started.add(process);
            return process;
        }
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
