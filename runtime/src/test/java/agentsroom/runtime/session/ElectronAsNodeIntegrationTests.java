package agentsroom.runtime.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

import agentsroom.runtime.harness.HarnessLauncher;
import agentsroom.runtime.shell.ShellLinkConfiguration;
import agentsroom.runtime.testsupport.MockAcpAgent;
import agentsroom.runtime.testsupport.TestRenderer;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;
import java.util.Map;
import java.util.function.Predicate;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * ADR 0003 decision 12, SCRUM-23: an adapter launched on the shell's Electron gets
 * {@code ELECTRON_RUN_AS_NODE=1}, and its preload keeps the variable to the adapter's own Node processes.
 * The node on the PATH poses as the shell's Electron here ({@code AGENTS_ROOM_NODE} names it, with
 * {@code AGENTS_ROOM_NODE_ELECTRON=1}), so the runtime launches the mock ACP agent exactly as it launches
 * codex-acp in the app; that the variable makes a real Electron run as Node is the desktop e2e's part
 * ({@code e2e/desktop/agentRun.spec.ts}).
 */
@SpringBootTest(
        webEnvironment = WebEnvironment.RANDOM_PORT,
        properties = {
            "AGENTS_ROOM_TOKEN=" + ElectronAsNodeIntegrationTests.TOKEN,
            ShellLinkConfiguration.ENABLED_PROPERTY + "=false"
        })
@ExtendWith(OutputCaptureExtension.class)
class ElectronAsNodeIntegrationTests {

    static final String TOKEN = "electron-as-node-it-token-xxxxxxxxxxxxxxxxx";

    private static final Duration WAIT = Duration.ofSeconds(20);
    private static final Path ROOT = createTempDirectory();
    private static final Path DATA_DIR = ROOT.resolve("data");
    private static final Path SCENARIO = ROOT.resolve("scenario.json");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("AGENTS_ROOM_DATA_DIR", DATA_DIR::toString);
        registry.add(HarnessLauncher.CODEX_COMMAND, () -> MockAcpAgent.command(SCENARIO));
        registry.add(HarnessLauncher.NODE, () -> MockAcpAgent.node().orElseThrow().toString());
        registry.add(HarnessLauncher.NODE_ELECTRON, () -> "1");
    }

    @BeforeAll
    static void needsNode() throws IOException {
        Assumptions.assumeTrue(MockAcpAgent.node().isPresent(),
                "node is not on the PATH: these tests run the mock ACP agent with it");
        Files.createDirectories(DATA_DIR);
    }

    @AfterAll
    static void deleteFiles() {
        try (Stream<Path> paths = Files.walk(ROOT)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
        } catch (IOException e) {
            // A leftover temp folder is harmless.
        }
    }

    private final JsonMapper json = JsonMapper.builder().build();

    @LocalServerPort
    int port;

    @Test
    void theAdapterAndItsOwnNodeChildRunPreloadedAndNoCommandSeesElectronRunAsNode(CapturedOutput output)
            throws Exception {
        MockAcpAgent.useScenario(SCENARIO, "probe-run-as-node");
        Path project = Files.createDirectories(ROOT.resolve("project"));
        TestRenderer renderer = TestRenderer.connect(URI.create("ws://127.0.0.1:" + port + "/ws?token=" + TOKEN));
        try {
            until(renderer, frame -> type(frame).equals("snapshot"));
            renderer.send(json.writeValueAsString(Map.of("type", "openProject", "path", project.toString())));
            String agentId = until(renderer, frame -> type(frame).equals("agentsUpdated")).get("agents").get(0)
                    .get("agentId").asString();
            renderer.send(json.writeValueAsString(Map.of("type", "startAgent", "agentId", agentId)));
            until(renderer, frame -> event(frame, "agentStatusChanged")
                    && frame.get("event").get("data").get("status").asString().equals("ready"));

            renderer.send(json.writeValueAsString(Map.of("type", "sendPrompt", "agentId", agentId, "text", "Probe")));
            JsonNode report = until(renderer, frame -> event(frame, "toolCall")).get("event").get("data")
                    .get("toolCall").get("rawOutput");
            until(renderer, frame -> event(frame, "runEnded"));

            // The variable is there for the adapter's process: the launch passed it, the preload took it out.
            Path preload = DATA_DIR.resolve("runtime").resolve("electron-as-node.cjs");
            assertThat(output.getAll()).contains("--require " + preload, "with {ELECTRON_RUN_AS_NODE=1}");
            assertThat(report.get("adapter").get("preloaded").asBoolean()).isTrue();
            assertThat(report.get("adapter").get("sees").asBoolean()).as("gone before the adapter's code ran")
                    .isFalse();
            assertThat(report.get("adapter").get("commandSees").asBoolean()).as("a command the adapter runs")
                    .isFalse();
            // A child on the adapter's own executable (codex-acp's Codex) gets it back with the preload,
            // and what that child runs (Codex's commands) does not see it either.
            assertThat(report.get("codex").get("answered").asBoolean()).isTrue();
            assertThat(report.get("codex").get("preloaded").asBoolean()).isTrue();
            assertThat(report.get("codex").get("sees").asBoolean()).isFalse();
            assertThat(report.get("codex").get("commandSees").asBoolean()).isFalse();
            // The same through util.promisify(execFile), which still resolves {stdout, stderr}.
            assertThat(report.get("execFileChild").get("answered").asBoolean()).isTrue();
            assertThat(report.get("execFileChild").get("preloaded").asBoolean()).isTrue();
            assertThat(report.get("execFileChild").get("sees").asBoolean()).isFalse();
            assertThat(report.get("execFileChild").get("commandSees").asBoolean()).isFalse();

            renderer.send(json.writeValueAsString(Map.of("type", "stopAgent", "agentId", agentId)));
            until(renderer, frame -> event(frame, "agentStatusChanged")
                    && frame.get("event").get("data").get("status").asString().equals("offline"));
        } finally {
            renderer.close();
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

    private static Path createTempDirectory() {
        try {
            return Files.createTempDirectory("agents-room-electron-as-node-it");
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
