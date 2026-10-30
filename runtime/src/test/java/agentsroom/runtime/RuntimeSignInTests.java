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
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
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
 * Start-Process, which starts a long-lived browser stand-in (a sleeping node). The agent then runs a prompt
 * that starts an ordinary child, after the sign-in.
 */
@EnabledOnOs(OS.WINDOWS)
class RuntimeSignInTests {

    private static final String TOKEN = "jar-sign-in-token-value-xxxxxxxxxxxxxxxxxxx";
    private static final Duration STARTUP = Duration.ofSeconds(60);
    private static final Duration WAIT = Duration.ofSeconds(30);
    private static final Pattern READY_LINE = Pattern.compile("AGENTS_ROOM_READY \\{\"port\":(\\d+)\\}\n");

    /** What the runtime logs once the last sign-in ended (ChildProcessContainment). */
    private static final String CONTAINED_AGAIN =
            "No sign-in in progress: the runtime's job object holds every new process again";

    private final JsonMapper json = JsonMapper.builder().build();
    private final List<ProcessHandle> started = new ArrayList<>();

    @TempDir
    Path dir;

    private String bootJar;

    @BeforeEach
    void needsTheJarAndNode() {
        bootJar = System.getProperty(RuntimeJarTests.BOOT_JAR_PROPERTY);
        assumeTrue(bootJar != null && Files.isRegularFile(Path.of(bootJar)), "run through Gradle: needs the boot jar");
        assumeTrue(MockAcpAgent.node().isPresent(), "node is not on the PATH: the mock ACP agent runs on it");
    }

    @AfterEach
    void killLeftovers() throws IOException {
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
        for (ProcessHandle process : started) {
            process.descendants().forEach(ProcessHandle::destroyForcibly);
            process.destroyForcibly();
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
        // Short, but long enough for a cold PowerShell to open the stand-in while the sign-in still waits.
        Runtime runtime = startRuntime(Map.of("authenticate", "never", "browserLauncher", "exits"),
                "-Dagentsroom.runtime.acp.authenticate-timeout=8s");
        String agentId = runtime.openProject();
        runtime.renderer().send(json.writeValueAsString(Map.of("type", "startAgent", "agentId", agentId)));
        // While authenticate waits: the sign-in opened the browser and the adapter still runs.
        Map<String, ProcessHandle> pids = runtime.awaitPids("adapter", "startup-child", "browser");
        JsonNode error = until(runtime.renderer(), frame -> event(frame, "agentError")).get("event").get("data");
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
                && frame.get("event").get("data").get("status").asString().equals("ready"));
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
        keys.put("browserStandIn", List.of(MockAcpAgent.node().orElseThrow().toString(), "-e",
                "setInterval(() => {}, 1000)"));
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
        long deadline = System.nanoTime() + WAIT.toNanos();
        while (System.nanoTime() < deadline) {
            JsonNode frame = renderer.poll(Duration.ofNanos(Math.max(1, deadline - System.nanoTime())));
            if (frame != null && matches.test(frame)) {
                return frame;
            }
        }
        return fail("no matching frame within " + WAIT);
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

        /** The processes the mock reported that still run, by role; a launcher that exits is gone by now. */
        Map<String, ProcessHandle> pids() throws IOException {
            Map<String, ProcessHandle> processes = new HashMap<>();
            for (String line : Files.readAllLines(pidFile, StandardCharsets.UTF_8)) {
                String[] parts = line.split(" ");
                ProcessHandle.of(Long.parseLong(parts[1])).ifPresent(process -> {
                    processes.put(parts[0], process);
                    started.add(process);
                });
            }
            return processes;
        }

        /** {@link #pids()} once the mock reported every one of {@code roles} and they all run. */
        Map<String, ProcessHandle> awaitPids(String... roles) throws Exception {
            long deadline = System.nanoTime() + WAIT.toNanos();
            while (true) {
                Map<String, ProcessHandle> processes = Files.isRegularFile(pidFile) ? pids() : Map.of();
                if (processes.keySet().containsAll(List.of(roles))) {
                    return processes;
                }
                if (System.nanoTime() > deadline) {
                    return fail("the mock reported " + processes.keySet() + " within " + WAIT + ", not all of "
                            + List.of(roles));
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
