package agentsroom.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import agentsroom.runtime.config.RuntimeEnvironment;
import agentsroom.runtime.harness.HarnessLauncher;
import agentsroom.runtime.testsupport.MockAcpAgent;
import agentsroom.runtime.testsupport.TestProcesses;
import agentsroom.runtime.testsupport.TestProcesses.StreamCollector;
import agentsroom.runtime.testsupport.TestRenderer;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The browser of a Codex sign-in outlives the runtime, on Windows, where the runtime's job object kills
 * everything else it started (SCRUM-65 AC1, SCRUM-22). The real boot jar runs the mock ACP agent as the
 * Codex adapter; the mock's sign-in "opens the login page" the way codex-acp does, through PowerShell's
 * Start-Process, which starts a long-lived browser stand-in (a sleeping node named like a browser,
 * {@link MockAcpAgent#browserStandIn}). The agent then runs a prompt that starts an ordinary child, after
 * the sign-in.
 *
 * <p>The sign-in goes on once PowerShell exited, which a cold PowerShell on a fresh CI machine makes slow:
 * the class starts one first, and the waits on the sign-in are long. {@value #LAUNCHER_DELAY_VARIABLE}
 * (milliseconds) makes the mock's launcher wait that much longer before it starts the stand-in, to try
 * these tests as on a slow machine.
 */
@EnabledOnOs(OS.WINDOWS)
class RuntimeSignInTests {

    private static final String TOKEN = "jar-sign-in-token-value-xxxxxxxxxxxxxxxxxxx";
    private static final Duration STARTUP = Duration.ofSeconds(60);
    private static final Duration WAIT = Duration.ofSeconds(30);
    /** A sign-in's own wait: PowerShell starts the stand-in, then exits, before the sign-in goes on. */
    private static final Duration SIGN_IN_WAIT = Duration.ofSeconds(90);
    /**
     * The time limit of the sign-in that never completes: long enough for a cold PowerShell to start the
     * stand-in while the sign-in still waits.
     */
    private static final Duration SIGN_IN_TIMEOUT = Duration.ofSeconds(45);
    private static final String LAUNCHER_DELAY_VARIABLE = "AGENTS_ROOM_TEST_BROWSER_LAUNCHER_DELAY_MS";
    private static final Pattern READY_LINE = Pattern.compile("AGENTS_ROOM_READY \\{\"port\":(\\d+)\\}\n");

    /** What the runtime logs once the last sign-in ended (ChildProcessContainment). */
    private static final String CONTAINED_AGAIN =
            "No sign-in in progress: the runtime's job object holds every new process again";

    private final JsonMapper json = JsonMapper.builder().build();
    /** A set: awaitPids reads the pid file again and again. */
    private final Set<ProcessHandle> started = new LinkedHashSet<>();

    @TempDir
    Path dir;

    private String bootJar;

    /**
     * Starts PowerShell once, the way the mock's launcher does, with the environment the runtime gives the
     * mock (the same profile folders, no PSModulePath): the first PowerShell on a fresh machine can take
     * longer than a sign-in waits.
     */
    @BeforeAll
    static void warmUpPowerShell() throws Exception {
        Path powerShell = Path.of(System.getenv().getOrDefault("SystemRoot", "C:\\Windows"), "System32",
                "WindowsPowerShell", "v1.0", "powershell.exe");
        ProcessBuilder builder = new ProcessBuilder(powerShell.toString(), "-NoProfile", "-NonInteractive",
                "-Command", "Start-Process -FilePath cmd.exe -ArgumentList '/d','/c','exit' -WindowStyle Hidden -Wait")
                .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD);
        builder.environment().keySet().removeIf(name -> name.equalsIgnoreCase("PSModulePath"));
        Process process = builder.start();
        if (!process.waitFor(SIGN_IN_WAIT.toSeconds(), TimeUnit.SECONDS)) {
            process.destroyForcibly();
        }
    }

    @BeforeEach
    void needsTheJarAndNode() {
        bootJar = System.getProperty(RuntimeJarTests.BOOT_JAR_PROPERTY);
        assumeTrue(bootJar != null && Files.isRegularFile(Path.of(bootJar)), "run through Gradle: needs the boot jar");
        assumeTrue(MockAcpAgent.node().isPresent(), "node is not on the PATH: the mock ACP agent runs on it");
    }

    /** Also waits for them, so that nothing of a test runs on into the next one. */
    @AfterEach
    void killLeftovers() throws IOException, InterruptedException {
        // The stand-ins too: nobody else ends them. The pid file also names the ones a failed test never
        // got to read.
        Path pids = dir.resolve("pids.txt");
        if (Files.isRegularFile(pids)) {
            for (String line : Files.readAllLines(pids, StandardCharsets.UTF_8)) {
                String[] parts = line.split(" ");
                if (parts.length == 2) {
                    ProcessHandle.of(Long.parseLong(parts[1])).ifPresent(started::add);
                }
            }
        }
        Set<ProcessHandle> all = new LinkedHashSet<>();
        for (ProcessHandle process : started) {
            process.descendants().forEach(all::add);
            all.add(process);
        }
        all.forEach(ProcessHandle::destroyForcibly);
        // isAlive, not onExit: onExit waits on the pid, which a process started since may have taken.
        long deadline = System.nanoTime() + WAIT.toNanos();
        while (all.stream().anyMatch(ProcessHandle::isAlive) && System.nanoTime() < deadline) {
            Thread.sleep(50);
        }
    }

    @Test
    void theBrowserOfASignInOutlivesAKilledRuntimeWhileTheAdaptersProcessesDie() throws Exception {
        SignedIn agent = signInAndRun("exits");

        // TerminateProcess: the runtime runs no code, only its job object ends what it started.
        agent.runtime().destroyForcibly();

        assertDies(agent.pid("adapter"), agent.pid("startup-child"), agent.pid("child"));
        assertThat(agent.pid("browser").isAlive()).as("the user's browser").isTrue();
    }

    @Test
    void theBrowserOfASignInOutlivesAnOrderlyExitEvenAsADescendant() throws Exception {
        // The launcher stays, so the browser is still the adapter's descendant when the runtime exits:
        // the adapter's tree kill and the runtime's cleanup must both spare it.
        SignedIn agent = signInAndRun("stays");
        assertThat(agent.runtime().toHandle().descendants()).contains(agent.pid("browser-launcher"),
                agent.pid("browser"));

        agent.runtime().getOutputStream().close();

        assertThat(agent.runtime().waitFor(WAIT.toSeconds(), TimeUnit.SECONDS)).as("exit after stdin EOF").isTrue();
        assertThat(agent.runtime().exitValue()).isZero();
        // The launcher (PowerShell) started during the sign-in too, but Node's libuv put it back into the
        // adapter's jobs (into its own job, nested there): only what the launcher started got out.
        assertDies(agent.pid("adapter"), agent.pid("startup-child"), agent.pid("child"), agent.pid("browser-launcher"));
        assertThat(agent.pid("browser").isAlive()).as("the user's browser").isTrue();
        assertThat(agent.log().awaitEnd(WAIT)).contains("Shutting down: stdin closed");
    }

    /**
     * A sign-in that never completes ends at the runtime's time limit ({@code AUTH_FAILED}): the browser it
     * opened stays, and the jobs hold every new process again (the release ends on that path too, not only
     * on success).
     */
    @Test
    void theBrowserOfASignInThatTimedOutOutlivesTheRuntimeAndTheJobsContainAgain() throws Exception {
        Runtime runtime = startRuntime(Map.of("authenticate", "never", "browserLauncher", "exits"),
                "-Dagentsroom.runtime.acp.authenticate-timeout=" + SIGN_IN_TIMEOUT.toSeconds() + "s");
        String agentId = runtime.openProject();
        runtime.renderer().send(json.writeValueAsString(Map.of("type", "startAgent", "agentId", agentId)));
        // While authenticate waits: the sign-in opened the browser and the adapter still runs.
        Map<String, ProcessHandle> pids = runtime.awaitPids(SIGN_IN_TIMEOUT, "adapter", "startup-child", "browser");
        JsonNode error = until(runtime.renderer(), frame -> event(frame, "agentError"), SIGN_IN_TIMEOUT.plus(WAIT))
                .get("event").get("data");
        assertThat(error.get("code").asString()).isEqualTo("AUTH_FAILED");
        assertThat(error.get("stage").asString()).isEqualTo("authenticate");
        awaitLog(runtime.log(), "Adapter process " + pids.get("adapter").pid() + " no longer signs in");
        awaitLog(runtime.log(), CONTAINED_AGAIN);

        runtime.process().destroyForcibly();

        assertDies(pids.get("adapter"), pids.get("startup-child"));
        assertThat(pids.get("browser").isAlive()).as("the user's browser, after a failed sign-in").isTrue();
    }

    /**
     * Starts the runtime with the mock as Codex, opens a project, starts its agent (which signs in) and
     * runs one prompt (which starts a child), and returns the processes the mock reported.
     */
    private SignedIn signInAndRun(String launcher) throws Exception {
        Runtime runtime = startRuntime(Map.of("browserLauncher", launcher,
                "prompts", List.of(Map.of("stopReason", "end_turn", "steps", List.of(Map.of("spawnChild", true))))));
        String agentId = runtime.openProject();
        runtime.renderer().send(json.writeValueAsString(Map.of("type", "startAgent", "agentId", agentId)));
        until(runtime.renderer(), frame -> event(frame, "agentStarting") && frame.get("event").get("data").get("stage")
                .asString().equals("authenticate"));
        until(runtime.renderer(), frame -> event(frame, "agentStatusChanged")
                && frame.get("event").get("data").get("status").asString().equals("ready"), SIGN_IN_WAIT);
        awaitLog(runtime.log(), CONTAINED_AGAIN);
        runtime.renderer().send(json.writeValueAsString(Map.of("type", "sendPrompt", "agentId", agentId, "text", "go")));
        until(runtime.renderer(), frame -> event(frame, "runEnded"));

        Map<String, ProcessHandle> processes = runtime.pids();
        assertThat(processes).containsKeys("adapter", "startup-child", "browser", "child");
        return new SignedIn(runtime.process(), runtime.log(), processes);
    }

    /**
     * Starts the boot jar with the mock as Codex: logged out, so starting the agent signs in, and the sign-in
     * opens the browser stand-in; the mock starts a child at once (as codex-acp starts Codex). {@code
     * overrides} change that scenario.
     */
    private Runtime startRuntime(Map<String, Object> overrides, String... jvmOptions) throws Exception {
        Path pids = dir.resolve("pids.txt");
        Path scenario = dir.resolve("scenario.json");
        Map<String, Object> keys = new LinkedHashMap<>();
        keys.put("extends", "auth-required");
        keys.put("childAtStart", true);
        keys.put("browserStandIn", List.of(MockAcpAgent.browserStandIn().toString(), "-e",
                "setInterval(() => {}, 1000)"));
        keys.put("browserLauncherDelayMs", launcherDelayMs());
        keys.put("pidFile", pids.toString());
        keys.putAll(overrides);
        MockAcpAgent.useScenarioJson(scenario, json.writeValueAsString(keys));

        List<String> command = new ArrayList<>(List.of(TestProcesses.javaExecutable(), "-Dstdout.encoding=UTF-8",
                "-Dstderr.encoding=UTF-8", "-Dfile.encoding=UTF-8"));
        command.addAll(List.of(jvmOptions));
        command.addAll(List.of("-jar", bootJar));
        ProcessBuilder builder = new ProcessBuilder(command);
        Map<String, String> environment = builder.environment();
        environment.put(RuntimeEnvironment.TOKEN, TOKEN);
        environment.put(RuntimeEnvironment.DATA_DIR, Files.createDirectories(dir.resolve("data")).toString());
        environment.put(RuntimeEnvironment.ALLOWED_ORIGINS, "app://bundle");
        environment.put(HarnessLauncher.CODEX_COMMAND, MockAcpAgent.command(scenario));
        Process process = builder.start();
        started.add(process.toHandle());
        StreamCollector log = TestProcesses.collect(process.getErrorStream());
        String readyLine =
                new String(TestProcesses.readLineBytes(process.getInputStream(), STARTUP), StandardCharsets.UTF_8);
        Matcher ready = READY_LINE.matcher(readyLine);
        assertThat(ready.matches()).as("ready line %s, log %s", readyLine, log.text()).isTrue();
        TestRenderer renderer =
                TestRenderer.connect(URI.create("ws://127.0.0.1:" + ready.group(1) + "/ws?token=" + TOKEN));
        until(renderer, frame -> type(frame).equals("snapshot"));
        return new Runtime(process, log, renderer, pids, dir.resolve("project"));
    }

    /** The extra wait of the mock's launcher: {@value #LAUNCHER_DELAY_VARIABLE}, else none. */
    private static long launcherDelayMs() {
        String delay = System.getenv(LAUNCHER_DELAY_VARIABLE);
        return delay == null || delay.isBlank() ? 0 : Long.parseLong(delay.strip());
    }

    private static void awaitLog(StreamCollector log, String line) throws InterruptedException {
        long deadline = System.nanoTime() + WAIT.toNanos();
        while (!log.text().contains(line)) {
            if (System.nanoTime() > deadline) {
                fail("no \"" + line + "\" in the runtime log within " + WAIT + ":\n" + log.text());
            }
            Thread.sleep(50);
        }
    }

    private static JsonNode until(TestRenderer renderer, Predicate<JsonNode> matches) throws InterruptedException {
        return until(renderer, matches, WAIT);
    }

    private static JsonNode until(TestRenderer renderer, Predicate<JsonNode> matches, Duration wait)
            throws InterruptedException {
        long deadline = System.nanoTime() + wait.toNanos();
        while (System.nanoTime() < deadline) {
            JsonNode frame = renderer.poll(Duration.ofNanos(Math.max(1, deadline - System.nanoTime())));
            if (frame != null && matches.test(frame)) {
                return frame;
            }
        }
        return fail("no matching frame within " + wait);
    }

    private static String type(JsonNode frame) {
        return frame.get("type").asString();
    }

    private static boolean event(JsonNode frame, String kind) {
        return type(frame).equals("agentEvent") && frame.get("event").get("kind").asString().equals(kind);
    }

    private static void assertDies(ProcessHandle... processes) {
        for (ProcessHandle process : processes) {
            assertThat(process.onExit()).as("exit of process %s", process.pid()).succeedsWithin(WAIT);
        }
    }

    /** A started runtime jar, with a renderer connected and the mock's pid file. */
    private final class Runtime {

        private final Process process;
        private final StreamCollector log;
        private final TestRenderer renderer;
        private final Path pidFile;
        private final Path project;

        Runtime(Process process, StreamCollector log, TestRenderer renderer, Path pidFile, Path project) {
            this.process = process;
            this.log = log;
            this.renderer = renderer;
            this.pidFile = pidFile;
            this.project = project;
        }

        Process process() {
            return process;
        }

        StreamCollector log() {
            return log;
        }

        TestRenderer renderer() {
            return renderer;
        }

        /** Opens the project and returns the id of its seeded Codex agent. */
        String openProject() throws Exception {
            Files.createDirectories(project);
            renderer.send(json.writeValueAsString(Map.of("type", "openProject", "path", project.toString())));
            return until(renderer, frame -> type(frame).equals("agentsUpdated")).get("agents").get(0).get("agentId")
                    .asString();
        }

        /** The pids the mock reported so far, by role, whether they still run or not. */
        Map<String, Long> reported() throws IOException {
            Map<String, Long> pids = new LinkedHashMap<>();
            if (Files.isRegularFile(pidFile)) {
                for (String line : Files.readAllLines(pidFile, StandardCharsets.UTF_8)) {
                    String[] parts = line.split(" ");
                    if (parts.length == 2) {
                        pids.put(parts[0], Long.parseLong(parts[1]));
                    }
                }
            }
            return pids;
        }

        /** The processes the mock reported that still run, by role; a launcher that exits is gone by now. */
        Map<String, ProcessHandle> pids() throws IOException {
            Map<String, ProcessHandle> processes = new HashMap<>();
            reported().forEach((role, pid) -> ProcessHandle.of(pid).ifPresent(process -> {
                processes.put(role, process);
                started.add(process);
            }));
            return processes;
        }

        /**
         * {@link #pids()} once the mock reported every one of {@code roles} and they all run. A failure names
         * every role the mock reported, and whether it still runs.
         */
        Map<String, ProcessHandle> awaitPids(Duration wait, String... roles) throws Exception {
            long deadline = System.nanoTime() + wait.toNanos();
            while (true) {
                Map<String, ProcessHandle> processes = pids();
                if (processes.keySet().containsAll(List.of(roles))) {
                    return processes;
                }
                if (System.nanoTime() > deadline) {
                    Map<String, String> states = new LinkedHashMap<>();
                    reported().forEach((role, pid) -> states.put(role,
                            pid + (ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false) ? " running"
                                    : " exited")));
                    return fail("within " + wait + " the mock reported " + states + ", not all of " + List.of(roles)
                            + " running; runtime log:\n" + log.text());
                }
                Thread.sleep(50);
            }
        }
    }

    private record SignedIn(Process runtime, StreamCollector log, Map<String, ProcessHandle> processes) {

        ProcessHandle pid(String role) {
            assertThat(processes).as("running processes").containsKey(role);
            return processes.get(role);
        }
    }
}
