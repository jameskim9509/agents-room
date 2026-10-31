package agentsroom.runtime.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

import agentsroom.runtime.harness.AdapterEnvironment;
import agentsroom.runtime.harness.HarnessLauncher;
import agentsroom.runtime.shell.ShellLinkConfiguration;
import agentsroom.runtime.testsupport.ContractSchema;
import agentsroom.runtime.testsupport.ContractSchema.Verdict;
import agentsroom.runtime.testsupport.MockAcpAgent;
import agentsroom.runtime.testsupport.TestRenderer;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * The whole runtime driving the mock ACP agent ({@code e2e/desktop/fixtures/mock-acp-agent.cjs}) as its
 * Codex adapter, through a renderer WebSocket: SCRUM-23 (Codex over ACP), SCRUM-65 (the sign-in stage)
 * and SCRUM-22 (an adapter that ends never ends the runtime). The real runtime classes run end to end,
 * the SDK included; only the adapter is a mock, at its process boundary. Every frame a test receives is
 * checked against {@code core/asyncapi.yaml}.
 *
 * <p>The time limits are short so that the timeout paths run quickly.
 */
@SpringBootTest(
        webEnvironment = WebEnvironment.RANDOM_PORT,
        properties = {
            "AGENTS_ROOM_TOKEN=" + AcpAgentIntegrationTests.TOKEN,
            ShellLinkConfiguration.ENABLED_PROPERTY + "=false",
            "agentsroom.runtime.acp.initialize-timeout=3s",
            "agentsroom.runtime.acp.auth-status-wait=2s",
            "agentsroom.runtime.acp.authenticate-timeout=3s",
            "agentsroom.runtime.acp.session-new-timeout=3s",
            "agentsroom.runtime.acp.stop-cancel-wait=2s",
            "agentsroom.runtime.acp.close-timeout=5s"
        })
@ExtendWith(OutputCaptureExtension.class)
class AcpAgentIntegrationTests {

    static final String TOKEN = "acp-integration-token-xxxxxxxxxxxxxxxxxxxxx";

    private static final Duration WAIT = Duration.ofSeconds(20);
    private static final Path ROOT = createTempDirectory();
    private static final Path DATA_DIR = ROOT.resolve("data");
    private static final Path PROJECTS = ROOT.resolve("projects");
    private static final Path SCENARIO = ROOT.resolve("scenario.json");
    private static final Pattern ADAPTER_PID = Pattern.compile("\\[agent [^]]+] Adapter started \\(pid (\\d+)\\)");
    private static final Pattern CHILD_PID = Pattern.compile("child pid: (\\d+)");
    /** The mock's fake key, as it builds it. */
    private static final String FAKE_KEY = String.join("-", "sk", "mockmockmockmock1234");

    private static int projectCount;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("AGENTS_ROOM_DATA_DIR", DATA_DIR::toString);
        registry.add(HarnessLauncher.CODEX_COMMAND, () -> MockAcpAgent.command(SCENARIO));
    }

    @BeforeAll
    static void needsNode() throws IOException {
        Assumptions.assumeTrue(MockAcpAgent.node().isPresent(),
                "node is not on the PATH: these tests run the mock ACP agent with it");
        Files.createDirectories(DATA_DIR);
        Files.createDirectories(PROJECTS);
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
    private final List<Long> processesToKill = new ArrayList<>();
    private Conversation open;

    @LocalServerPort
    int port;

    @AfterEach
    void leaveTheAgentIdle() throws Exception {
        try {
            if (open != null && open.agentId != null && !open.idle()) {
                open.command("stopAgent");
                open.status("offline");
            }
        } finally {
            if (open != null) {
                open.renderer.close();
            }
            processesToKill.forEach(pid -> ProcessHandle.of(pid).ifPresent(ProcessHandle::destroyForcibly));
        }
    }

    // ── SCRUM-23: one Codex run end to end ────────────────────────────

    @Test
    void aRunStreamsNormalizedEventsInOrderAndTheSnapshotKeepsThem(CapturedOutput output) throws Exception {
        Conversation agent = openAgent("happy");

        agent.command("startAgent");
        agent.status("ready");
        agent.command("sendPrompt", "text", "Read the README");
        JsonNode ended = agent.event("runEnded");
        agent.status("ready");

        assertThat(agent.statuses()).containsExactly("starting", "ready", "running", "ready");
        assertThat(agent.stages()).containsExactly("spawn", "initialize", "session_new");
        JsonNode auth = agent.first("authStatus").get("data");
        assertThat(auth.get("kind").asString()).isEqualTo("account");
        assertThat(auth.get("label").asString()).isEqualTo("ChatGPT Plus");
        assertThat(String.join("\n", agent.renderer.texts())).as("identity fields are dropped (ADR 0004)")
                .doesNotContain("email", "mock-user@example.com");
        // Adapter strings are masked before they go out (ADR 0005 decision 11): a key inside one chunk,
        // one split across two chunks, or inside a tool call's input. assertContract also checks that no
        // frame carries the whole key. The split key's start is held back until its rest came, so no
        // live chunk shows a piece of it, and the text arrives whole and in order.
        assertThat(agent.events("agentMessageChunk")).filteredOn(event -> "msg-key".equals(text(event.get("data"),
                "messageId"))).singleElement().satisfies(event -> assertThat(event.get("data").get("text").asString())
                        .isEqualTo("A whole key: ****."));
        assertThat(agent.events("agentMessageChunk")).filteredOn(event -> "msg-1".equals(text(event.get("data"),
                "messageId"))).extracting(event -> event.get("data").get("text").asString())
                .containsExactly("The README is read, ", "git status is clean, ", "and the key ", "**** stays hidden.");
        assertThat(agent.lastToolCall("call-search").get("rawInput").get("note").asString()).isEqualTo("not ****");

        JsonNode started = agent.first("sessionStarted");
        String sessionId = started.get("data").get("sessionId").asString();
        assertThat(started.get("data").get("agentInfo").get("title").asString()).isEqualTo("Codex");
        assertThat(started.get("data").get("modes").get("currentModeId").asString()).isEqualTo("agent");
        assertThat(started.get("data").get("configOptions").get(0).get("category").asString()).isEqualTo("model");
        // Sent before the session/new answer, shown after the session started.
        assertThat(agent.kinds().indexOf("availableCommands")).isGreaterThan(agent.kinds().indexOf("sessionStarted"));

        // The ACP session cwd and the adapter's own working directory are the project's real path.
        String cwdLine = agent.events("agentMessageChunk").stream()
                .map(event -> event.get("data").get("text").asString())
                .filter(text -> text.startsWith("cwd: "))
                .findFirst().orElseThrow();
        assertThat(cwdLine).isEqualTo("cwd: " + agent.projectPath + " | process cwd: " + agent.projectPath);

        // The envelope: seq from 1 without gaps, the session on every event after it opened, the run on run events.
        List<JsonNode> events = agent.events();
        for (int i = 0; i < events.size(); i++) {
            JsonNode event = events.get(i);
            assertThat(event.get("seq").asLong()).isEqualTo(i + 1);
            assertThat(event.get("projectId").asString()).isEqualTo(agent.projectId);
            assertThat(event.get("agentId").asString()).isEqualTo(agent.agentId);
        }
        String runId = agent.first("runStarted").get("data").get("runId").asString();
        assertThat(agent.first("runStarted").get("data").get("prompt").asString()).isEqualTo("Read the README");
        agent.eventsBetween("runStarted", "runEnded").forEach(event -> {
            assertThat(event.get("runId").asString()).as(event.toString()).isEqualTo(runId);
            assertThat(event.get("sessionId").asString()).isEqualTo(sessionId);
        });
        assertThat(agent.first("availableCommands").get("runId").isNull()).as("a session-level update").isTrue();

        // Tool calls: one call per toolCallId, merged (ADR 0005 decision 6).
        JsonNode read = agent.lastToolCall("call-read");
        assertThat(read.get("status").asString()).isEqualTo("completed");
        assertThat(read.get("kind").asString()).isEqualTo("read");
        assertThat(read.get("title").asString()).isEqualTo("Read file 'README.md'");
        assertThat(read.get("locations").get(0).get("path").asString()).endsWith("README.md");
        assertThat(read.get("rawInput").get("path").asString()).endsWith("README.md");
        assertThat(read.get("rawOutput").get("content").asString()).isEqualTo("# demo");
        assertThat(read.get("rawOutput").get("token").asString()).as("masked by key name").isEqualTo("****");
        assertThat(agent.events("toolCall")).extracting(event -> event.get("data").get("toolCall").get("toolCallId")
                .asString()).containsExactly("call-read", "call-search", "call-edit", "call-exec", "call-other");
        JsonNode exec = agent.lastToolCall("call-exec");
        assertThat(exec.get("kind").asString()).isEqualTo("execute");
        assertThat(exec.get("rawInput").get("command").get(0).asString()).isEqualTo("git");
        assertThat(exec.get("vendorMeta").has("terminal_exit")).as("the latest _meta, not merged").isTrue();
        assertThat(exec.get("vendorMeta").has("terminal_info")).isFalse();
        assertThat(agent.lastToolCall("call-edit").get("content").get(0).get("type").asString()).isEqualTo("diff");
        JsonNode other = agent.lastToolCall("call-other");
        assertThat(other.get("kind").asString()).as("an unknown kind is generic work (R6)").isEqualTo("other");
        assertThat(other.has("status")).as("a status never reported stays absent").isFalse();

        assertThat(agent.first("plan").get("data").get("entries")).hasSize(3);
        assertThat(agent.first("unknownUpdate").get("data").get("type").asString()).isEqualTo("subagent_spawned");
        assertThat(agent.events("agentThoughtChunk")).hasSize(1);

        assertThat(ended.get("data").get("outcome").asString()).isEqualTo("completed");
        assertThat(ended.get("data").get("stopReason").asString()).isEqualTo("end_turn");
        assertThat(ended.get("data").get("unfinishedToolCalls").size())
                .as("call-other never finished").isEqualTo(1);

        // A renderer that connects now gets it all back from the snapshot (ADR 0005 decision 2).
        JsonNode snapshot = agent.reconnectSnapshot();
        assertThat(snapshot.get("status").asString()).isEqualTo("ready");
        assertThat(snapshot.get("lastSeq").asLong()).isEqualTo(events.size());
        assertThat(snapshot.get("sessionId").asString()).isEqualTo(sessionId);
        assertThat(snapshot.get("runId").isNull()).isTrue();
        assertThat(snapshot.get("toolCalls").isEmpty()).isTrue();
        assertThat(snapshot.get("prompt").asString()).isEqualTo("Read the README");
        assertThat(snapshot.get("lastRun").get("outcome").asString()).isEqualTo("completed");
        assertThat(snapshot.get("authStatus").get("kind").asString()).isEqualTo("account");
        JsonNode session = snapshot.get("session");
        assertThat(session.get("agentInfo").get("title").asString()).isEqualTo("Codex");
        assertThat(session.get("plan")).hasSize(3);
        assertThat(session.get("usage").get("used").asLong()).isEqualTo(1200);
        assertThat(session.get("sessionInfo").get("title").asString()).isEqualTo("README and git status");
        assertThat(session.get("availableCommands").get(0).get("name").asString()).isEqualTo("review");
        assertThat(session.get("configOptions").get(0).get("currentValue").asString()).isEqualTo("gpt-mock");
        List<JsonNode> recent = stream(snapshot.get("recentEvents")).toList();
        assertThat(recent).extracting(event -> event.get("kind").asString()).doesNotContain("agentThoughtChunk");
        List<String> reply = recent.stream()
                .filter(event -> event.get("kind").asString().equals("agentMessageChunk"))
                .filter(event -> "msg-1".equals(text(event.get("data"), "messageId")))
                .map(event -> event.get("data").get("text").asString())
                .toList();
        assertThat(reply).containsExactly("The README is read, git status is clean, and the key **** stays hidden.");

        // stderr went to the log with the agent's prefix, masked (ADR 0003 decision 9).
        assertThat(output.getAll()).containsPattern("\\[agent Codex [0-9a-f]{8}] mock-acp-agent: starting")
                .contains("OPENAI_API_KEY=****").doesNotContain(FAKE_KEY);

        long adapterPid = adapterPid(output);
        agent.command("stopAgent");
        JsonNode sessionEnded = agent.event("sessionEnded");
        agent.status("offline");
        assertThat(sessionEnded.get("data").get("reason").asString()).isEqualTo("stopped");
        assertThat(agent.statuses()).endsWith("ready", "offline");
        awaitGone(adapterPid);
        agent.assertContract();
    }

    @Test
    void anApiKeyLoginIsNeverReplacedBySignIn() throws Exception {
        Conversation agent = openAgent("api-key-login");

        agent.command("startAgent");
        agent.status("ready");

        assertThat(agent.stages()).as("D8: no authenticate over an existing login")
                .containsExactly("spawn", "initialize", "session_new");
        assertThat(agent.first("authStatus").get("data").get("kind").asString()).isEqualTo("api_key");
        agent.assertContract();
    }

    /**
     * D8 (ADR 0004 auth method table): a reported login is never replaced by a ChatGPT sign-in, even when
     * session/new still asks for authentication. The start fails AUTH_REQUIRED at session_new instead.
     */
    @ParameterizedTest
    @CsvSource({"api-key-login, api_key", "happy, account"})
    void aReportedLoginIsNeverReplacedEvenWhenSessionNewAsksForAuthentication(String scenario, String kind)
            throws Exception {
        Conversation agent = openAgentWith("{\"extends\":\"" + scenario + "\",\"loggedIn\":false}");

        agent.command("startAgent");
        JsonNode error = agent.event("agentError");
        agent.status("error");

        assertThat(agent.first("authStatus").get("data").get("kind").asString()).isEqualTo(kind);
        assertThat(agent.stages()).as("no authenticate stage").containsExactly("spawn", "initialize", "session_new");
        assertThat(error.get("data").get("code").asString()).isEqualTo("AUTH_REQUIRED");
        assertThat(error.get("data").get("stage").asString()).isEqualTo("session_new");
        agent.assertContract();
    }

    // ── SCRUM-65: the sign-in stage ────────────────────────────────────

    @Test
    void notLoggedInSignsInBeforeTheSessionAndTheCardShowsIt() throws Exception {
        Conversation agent = openAgent("auth-required");

        agent.command("startAgent");
        agent.status("ready");

        assertThat(agent.stages()).containsExactly("spawn", "initialize", "authenticate", "session_new");
        assertThat(agent.events("authStatus")).extracting(event -> event.get("data").get("kind").asString())
                .containsExactly("none", "account");
        assertThat(agent.frames("agentsUpdated")).extracting(frame -> frame.get("agents").get(0).get("readiness")
                .asString()).containsExactly("configured", "auth_required", "configured");
        agent.assertContract();
    }

    /**
     * D8: an adapter that advertises the status push but pushes nothing (codex-acp when its account read
     * fails) is waited for (auth-status-wait, 2 s here); without the capability the runtime does not wait.
     * Either way session/new answering -32000 signs in and retries once.
     */
    @ParameterizedTest
    @CsvSource({"auth-no-push, true", "no-auth-capability, false"})
    void withoutAStatusPushSessionNewAskingForAuthSignsInAndRetriesOnce(String scenario, boolean waits,
            CapturedOutput output) throws Exception {
        Conversation agent = openAgent(scenario);

        agent.command("startAgent");
        agent.status("ready");

        assertThat(agent.stages()).containsExactly("spawn", "initialize", "session_new", "authenticate", "session_new");
        String waited = "[Codex " + agent.agentId.substring(0, 8) + "] No auth status within 2000 ms";
        if (waits) {
            assertThat(output.getAll()).contains(waited);
            long initialize = agent.stageReceivedAt("initialize");
            long sessionNew = agent.stageReceivedAt("session_new");
            assertThat(sessionNew - initialize).as("the wait for the status").isGreaterThanOrEqualTo(1900);
        } else {
            assertThat(output.getAll()).doesNotContain(waited);
        }
        agent.assertContract();
    }

    @ParameterizedTest
    @CsvSource({
        "auth-fails, AUTH_FAILED, authenticate, Sign-in did not complete",
        "auth-hangs, AUTH_FAILED, authenticate, did not complete within 3 s",
        "auth-still-required, AUTH_REQUIRED, session_new, Authentication required",
        "no-chatgpt, AUTH_FAILED, authenticate, does not offer the 'chat-gpt' sign-in"
    })
    void signInFailuresAreAuthErrorsNotAdapterErrors(String scenario, String code, String stage, String message)
            throws Exception {
        Conversation agent = openAgent(scenario);

        agent.command("startAgent");
        JsonNode error = agent.event("agentError");
        agent.status("error");

        assertThat(error.get("data").get("code").asString()).isEqualTo(code);
        assertThat(error.get("data").get("stage").asString()).isEqualTo(stage);
        assertThat(error.get("data").get("message").asString()).contains(message);
        assertThat(agent.kinds()).as("no session was opened").doesNotContain("sessionStarted", "sessionEnded");
        JsonNode snapshot = agent.reconnectSnapshot();
        assertThat(snapshot.get("lastError").get("code").asString()).isEqualTo(code);
        agent.assertContract();
    }

    // ── Permissions (ADR 0005 decision 7, D16) ────────────────────────

    @Test
    void aPermissionRequestWaitsForTheUsersOptionAndOnlyAnOfferedOneIsAccepted() throws Exception {
        Conversation agent = openAgent("permission");
        agent.command("startAgent");
        agent.status("ready");

        agent.command("sendPrompt", "text", "Clean the build");
        JsonNode requested = agent.event("permissionRequested");
        agent.status("awaiting_permission");
        String permissionId = requested.get("data").get("permissionId").asString();
        assertThat(requested.get("data").get("options")).extracting(option -> option.get("kind").asString())
                .containsExactly("allow_once", "allow_always", "reject_once");
        assertThat(requested.get("data").get("toolCall").get("title").asString()).isEqualTo("Run rm -rf build");

        // A renderer that connects now sees the pending request and the run.
        JsonNode midRun = agent.reconnectSnapshot();
        assertThat(midRun.get("status").asString()).isEqualTo("awaiting_permission");
        assertThat(midRun.get("runId").isNull()).isFalse();
        assertThat(midRun.get("pendingPermissions").get(0).get("permissionId").asString()).isEqualTo(permissionId);
        assertThat(midRun.get("toolCalls").get(0).get("toolCallId").asString()).isEqualTo("call-rm");

        agent.send(respond(permissionId, "no-such-option"));
        JsonNode rejected = agent.frame("commandRejected");
        assertThat(rejected.get("reason").asString()).isEqualTo("INVALID_MESSAGE");
        assertThat(rejected.get("permissionId").asString()).isEqualTo(permissionId);
        assertThat(rejected.get("agentId").asString()).isEqualTo(agent.agentId);

        agent.send(respond(permissionId, "allow_once"));
        JsonNode resolved = agent.event("permissionResolved");
        assertThat(resolved.get("data").get("outcome").asString()).isEqualTo("selected");
        assertThat(resolved.get("data").get("optionId").asString()).isEqualTo("allow_once");
        JsonNode said = agent.event("agentMessageChunk",
                event -> event.get("data").get("text").asString().startsWith("permission:"));
        assertThat(said.get("data").get("text").asString()).isEqualTo("permission: selected allow_once");
        assertThat(agent.event("runEnded").get("data").get("outcome").asString()).isEqualTo("completed");
        agent.status("ready");
        assertThat(agent.statuses()).containsExactly("starting", "ready", "running", "awaiting_permission", "running",
                "ready");

        agent.send(respond(permissionId, "allow_once"));
        assertThat(agent.frame("commandRejected").get("reason").asString()).isEqualTo("UNKNOWN_PERMISSION");
        agent.assertContract();
    }

    /**
     * ADR 0005 decision 7: the adapter withdraws its request ($/cancel_request). The SDK answers it
     * -32800; the runtime closes it cancelled, goes back to running, and refuses a late answer.
     */
    @Test
    void aPermissionRequestTheAdapterWithdrawsIsClosedCancelledAndCannotBeAnswered() throws Exception {
        Conversation agent = openAgent("permission-withdrawn");
        agent.command("startAgent");
        agent.status("ready");

        agent.command("sendPrompt", "text", "Push it");
        String permissionId = agent.event("permissionRequested").get("data").get("permissionId").asString();
        agent.status("awaiting_permission");
        JsonNode resolved = agent.event("permissionResolved");
        agent.status("running");

        assertThat(resolved.get("data").get("permissionId").asString()).isEqualTo(permissionId);
        assertThat(resolved.get("data").get("outcome").asString()).isEqualTo("cancelled");
        assertThat(resolved.get("data").has("optionId")).isFalse();
        assertThat(agent.event("runEnded").get("data").get("outcome").asString()).isEqualTo("completed");
        agent.status("ready");

        assertThat(agent.events("agentMessageChunk")).extracting(event -> event.get("data").get("text").asString())
                .as("the SDK answered the withdrawn request").contains("permission: error -32800");
        assertThat(agent.statuses()).containsExactly("starting", "ready", "running", "awaiting_permission", "running",
                "ready");
        assertThat(agent.events("permissionResolved")).hasSize(1);
        agent.send(respond(permissionId, "allow_once"));
        assertThat(agent.frame("commandRejected").get("reason").asString()).isEqualTo("UNKNOWN_PERMISSION");
        assertThat(agent.reconnectSnapshot().get("pendingPermissions").isEmpty()).isTrue();
        agent.assertContract();
    }

    // ── stopAgent (ADR 0005 transition table, D13) ─────────────────────

    @Test
    void aSecondPromptDuringARunIsRefusedAndStopCancelsTheRunFirst() throws Exception {
        Conversation agent = openAgent("long-run");
        agent.command("startAgent");
        agent.status("ready");
        agent.command("sendPrompt", "text", "Run the tests");
        agent.event("agentMessageChunk");

        agent.command("sendPrompt", "text", "And another thing");
        assertThat(agent.frame("commandRejected").get("reason").asString()).isEqualTo("INVALID_STATE");
        agent.command("startAgent");
        assertThat(agent.frame("commandRejected").get("reason").asString()).isEqualTo("INVALID_STATE");
        agent.command("checkConnection");
        assertThat(agent.frame("commandRejected").get("reason").asString()).isEqualTo("INVALID_STATE");

        agent.command("stopAgent");
        JsonNode ended = agent.event("runEnded");
        agent.status("offline");

        assertThat(agent.statuses()).containsExactly("starting", "ready", "running", "cancelling", "offline");
        assertThat(ended.get("data").get("outcome").asString()).as("the agent answered the cancel").isEqualTo("cancelled");
        assertThat(ended.get("data").get("stopReason").asString()).isEqualTo("cancelled");
        assertThat(ended.get("data").get("unfinishedToolCalls").get(0).get("derivedStatus").asString())
                .isEqualTo("cancelled");
        assertThat(agent.first("sessionEnded").get("data").get("reason").asString()).isEqualTo("stopped");
        assertThat(agent.kinds().indexOf("runEnded")).isLessThan(agent.kinds().indexOf("sessionEnded"));
        agent.command("stopAgent");
        assertThat(agent.frame("commandRejected").get("reason").asString()).isEqualTo("INVALID_STATE");
        agent.assertContract();
    }

    /**
     * ADR 0005 transition table: a cancel the adapter does not answer within stop-cancel-wait (2 s here)
     * ends the process anyway, and the run is failed without an error code.
     */
    @Test
    void aStopWhoseCancelGetsNoAnswerEndsTheProcessAndFailsTheRunWithoutAnErrorCode() throws Exception {
        Conversation agent = openAgent("ignores-cancel");
        agent.command("startAgent");
        agent.status("ready");
        agent.command("sendPrompt", "text", "Think");
        agent.event("agentMessageChunk");

        long stopAt = System.nanoTime();
        agent.command("stopAgent");
        JsonNode ended = agent.event("runEnded");
        agent.status("offline");
        Duration took = Duration.ofNanos(System.nanoTime() - stopAt);

        assertThat(agent.statuses()).endsWith("running", "cancelling", "offline");
        assertThat(ended.get("data").get("outcome").asString()).isEqualTo("failed");
        assertThat(ended.get("data").has("errorCode")).as("a stop, not an error").isFalse();
        assertThat(ended.get("data").has("stopReason")).as("no answer came").isFalse();
        assertThat(agent.first("sessionEnded").get("data").get("reason").asString()).isEqualTo("stopped");
        assertThat(agent.kinds()).doesNotContain("agentError");
        assertThat(took).as("waited for the cancel, then ended the process within the close timeout")
                .isBetween(Duration.ofMillis(1900), Duration.ofSeconds(2 + 5 + 3));
        agent.assertContract();
    }

    @Test
    void stopAnswersAPendingPermissionRequestCancelled() throws Exception {
        Conversation agent = openAgent("permission-then-wait");
        agent.command("startAgent");
        agent.status("ready");
        agent.command("sendPrompt", "text", "Write notes");
        String permissionId = agent.event("permissionRequested").get("data").get("permissionId").asString();
        agent.status("awaiting_permission");

        agent.command("stopAgent");
        JsonNode resolved = agent.event("permissionResolved");
        agent.status("offline");

        assertThat(resolved.get("data").get("permissionId").asString()).isEqualTo(permissionId);
        assertThat(resolved.get("data").get("outcome").asString()).isEqualTo("cancelled");
        assertThat(resolved.get("data").has("optionId")).isFalse();
        assertThat(agent.statuses()).endsWith("awaiting_permission", "cancelling", "offline");
        assertThat(agent.reconnectSnapshot().get("pendingPermissions").isEmpty()).isTrue();
        agent.assertContract();
    }

    @Test
    void stopEndsTheWholeProcessTree(CapturedOutput output) throws Exception {
        Conversation agent = openAgent("spawn-child");
        agent.command("startAgent");
        agent.status("ready");
        agent.command("sendPrompt", "text", "Start a helper");
        String said = agent.event("agentMessageChunk").get("data").get("text").asString();
        Matcher child = CHILD_PID.matcher(said);
        assertThat(child.find()).as(said).isTrue();
        long childPid = Long.parseLong(child.group(1));
        processesToKill.add(childPid);
        long adapterPid = adapterPid(output);
        assertThat(ProcessHandle.of(childPid)).isPresent();

        agent.command("stopAgent");
        agent.status("offline");

        // The mock leaves its child running (as codex.exe leaves git.exe): only the runtime's tree kill ends it.
        awaitGone(adapterPid);
        awaitGone(childPid);
        agent.assertContract();
    }

    // ── SCRUM-22: an adapter that ends or misbehaves ends that agent only ──

    @Test
    void aCrashMidRunFailsTheRunAndTheAgentAndANewStartIsANewProcess(CapturedOutput output) throws Exception {
        Conversation agent = openAgent("crash-mid-run");
        agent.command("startAgent");
        agent.status("ready");
        String firstSession = agent.first("sessionStarted").get("data").get("sessionId").asString();
        agent.command("sendPrompt", "text", "Build it");

        JsonNode error = agent.event("agentError");
        agent.status("error");

        JsonNode ended = agent.first("runEnded");
        assertThat(ended.get("data").get("outcome").asString()).as("never completed (R7)").isEqualTo("failed");
        assertThat(ended.get("data").get("errorCode").asString()).isEqualTo("PROCESS_EXITED");
        assertThat(ended.get("data").get("unfinishedToolCalls").get(0).get("derivedStatus").asString())
                .isEqualTo("incomplete");
        JsonNode sessionEnded = agent.first("sessionEnded");
        assertThat(sessionEnded.get("data").get("reason").asString()).isEqualTo("exited");
        assertThat(sessionEnded.get("data").get("exitCode").asInt()).isEqualTo(3);
        assertThat(error.get("data").get("code").asString()).isEqualTo("PROCESS_EXITED");
        assertThat(error.get("data").get("stage").asString()).isEqualTo("prompt");
        assertThat(error.get("data").get("message").asString()).contains("code 3");
        assertThat(agent.kinds()).containsSubsequence("runEnded", "sessionEnded", "agentError", "agentStatusChanged");

        // The runtime is still there; a new start is a new process and a new session.
        MockAcpAgent.useScenario(SCENARIO, "happy");
        agent.command("startAgent");
        JsonNode reopened = agent.event("sessionStarted",
                event -> !event.get("data").get("sessionId").asString().equals(firstSession));
        agent.status("ready");
        assertThat(reopened.get("data").get("sessionId").asString()).isNotEqualTo(firstSession);
        assertThat(agent.reconnectSnapshot().has("lastError")).as("only in error").isFalse();
        agent.assertContract();
    }

    @ParameterizedTest
    @CsvSource({"malformed-mid-run", "not-jsonrpc-mid-run"})
    void aMalformedLineMidRunIsAProtocolErrorThatAbortsTheSession(String scenario) throws Exception {
        Conversation agent = openAgent(scenario);
        agent.command("startAgent");
        agent.status("ready");
        agent.command("sendPrompt", "text", "Misbehave");

        JsonNode error = agent.event("agentError");
        agent.status("error");

        assertThat(error.get("data").get("code").asString()).isEqualTo("PROTOCOL_ERROR");
        assertThat(error.get("data").get("stage").asString()).isEqualTo("prompt");
        assertThat(agent.first("runEnded").get("data").get("errorCode").asString()).isEqualTo("PROTOCOL_ERROR");
        assertThat(agent.first("sessionEnded").get("data").get("reason").asString()).isEqualTo("aborted");
        agent.assertContract();
    }

    @ParameterizedTest
    @CsvSource({
        "exit-at-start, ADAPTER_START_FAILED, spawn",
        "malformed-at-start, ADAPTER_START_FAILED, spawn",
        "initialize-never, ACP_INIT_FAILED, initialize",
        "initialize-error, ACP_INIT_FAILED, initialize",
        "protocol-v2, PROTOCOL_UNSUPPORTED, initialize",
        "session-new-fails, SESSION_CREATE_FAILED, session_new",
        "session-new-never, SESSION_CREATE_FAILED, session_new"
    })
    void eachStartStageFailsWithItsOwnCode(String scenario, String code, String stage) throws Exception {
        Conversation agent = openAgent(scenario);

        agent.command("startAgent");
        JsonNode error = agent.event("agentError");
        agent.status("error");

        assertThat(error.get("data").get("code").asString()).isEqualTo(code);
        assertThat(error.get("data").get("stage").asString()).isEqualTo(stage);
        assertThat(agent.statuses()).containsExactly("starting", "error");
        assertThat(agent.kinds()).doesNotContain("sessionStarted", "sessionEnded");
        if (scenario.equals("session-new-fails")) {
            assertThat(error.get("data").get("message").asString()).as("the adapter's message, masked")
                    .contains("Internal error (OPENAI key ****)");
        }
        agent.assertContract();
    }

    @ParameterizedTest
    @CsvSource({"prompt-fails, PROMPT_FAILED", "prompt-auth-required, AUTH_REQUIRED"})
    void aPromptErrorFailsTheRunButKeepsTheSession(String scenario, String code) throws Exception {
        Conversation agent = openAgent(scenario);
        agent.command("startAgent");
        agent.status("ready");

        agent.command("sendPrompt", "text", "Do it");
        JsonNode ended = agent.event("runEnded");
        agent.status("ready");

        assertThat(ended.get("data").get("outcome").asString()).isEqualTo("failed");
        assertThat(ended.get("data").get("errorCode").asString()).isEqualTo(code);
        assertThat(agent.kinds()).doesNotContain("agentError", "sessionEnded");
        agent.assertContract();
    }

    @Test
    void aRefusalEndsTheRunAsRefused() throws Exception {
        Conversation agent = openAgent("refusal");
        agent.command("startAgent");
        agent.status("ready");

        agent.command("sendPrompt", "text", "Do something bad");

        assertThat(agent.event("runEnded").get("data").get("outcome").asString()).isEqualTo("refused");
        agent.status("ready");
        agent.assertContract();
    }

    /** ADR 0005 decision 5: the stop reason decides the outcome; one ACP v1 does not define is failed. */
    @ParameterizedTest
    @CsvSource({
        "max-tokens, limit_reached, max_tokens",
        "max-turn-requests, limit_reached, max_turn_requests",
        "unknown-stop, failed, some_new_reason"
    })
    void theStopReasonDecidesTheOutcome(String scenario, String outcome, String stopReason) throws Exception {
        Conversation agent = openAgent(scenario);
        agent.command("startAgent");
        agent.status("ready");

        agent.command("sendPrompt", "text", "Go on and on");
        JsonNode ended = agent.event("runEnded");
        agent.status("ready");

        assertThat(ended.get("data").get("outcome").asString()).isEqualTo(outcome);
        assertThat(ended.get("data").get("stopReason").asString()).as("passed through as received")
                .isEqualTo(stopReason);
        assertThat(ended.get("data").has("errorCode")).isFalse();
        assertThat(agent.kinds()).doesNotContain("agentError", "sessionEnded");
        agent.assertContract();
    }

    /**
     * SCRUM-22: messages that are JSON-RPC but whose params miss required fields are rejected by the SDK,
     * which logs and skips them (ADR 0005 transition table). The runtime never sees them: no event, no
     * error, and the run goes on.
     */
    @Test
    void anUpdateWhoseParamsTheSdkRejectsIsSkippedAndTheRunGoesOn(CapturedOutput output) throws Exception {
        Conversation agent = openAgent("bad-params-mid-run");
        agent.command("startAgent");
        agent.status("ready");

        agent.command("sendPrompt", "text", "Send something odd");
        JsonNode ended = agent.event("runEnded");
        agent.status("ready");

        assertThat(ended.get("data").get("outcome").asString()).isEqualTo("completed");
        assertThat(agent.events("agentMessageChunk")).extracting(event -> event.get("data").get("text").asString())
                .containsExactly("After the bad updates.");
        assertThat(agent.kinds()).doesNotContain("toolCall", "unknownUpdate", "agentError");
        assertThat(output.getAll()).as("the SDK's diagnostic").contains("session/update failed and was skipped");
        agent.assertContract();
    }

    /**
     * SCRUM-22: a burst of updates goes out one event per update, in order and without batching, while a
     * renderer that connects meanwhile still gets its snapshot; the tail keeps the newest 200.
     */
    @Test
    void aBurstOfUpdatesArrivesInOrderAndTheTailKeepsTheNewest200() throws Exception {
        Conversation agent = openAgent("burst");
        agent.command("startAgent");
        agent.status("ready");

        agent.command("sendPrompt", "text", "Stream a lot");
        JsonNode midBurst = agent.reconnectSnapshot();
        agent.event("runEnded");

        List<String> texts = agent.events("agentMessageChunk").stream()
                .map(event -> event.get("data").get("text").asString())
                .toList();
        assertThat(texts).hasSize(300);
        assertThat(texts.getFirst()).isEqualTo("update 1");
        assertThat(texts.getLast()).isEqualTo("update 300");
        assertThat(midBurst.get("lastSeq").asLong()).as("a snapshot taken during the burst").isPositive();
        JsonNode after = agent.reconnectSnapshot();
        List<JsonNode> recent = stream(after.get("recentEvents")).toList();
        assertThat(recent).hasSize(200);
        assertThat(recent.getLast().get("seq").asLong()).isEqualTo(after.get("lastSeq").asLong());
        agent.assertContract();
    }

    /**
     * ADR 0005 decision 11, SCRUM-65: a key split across two chunks at every position, and across three
     * chunks at every pair of positions inside it, never shows in any frame, live or in the snapshot;
     * every message arrives whole and in order, and the snapshot's tail is the live text as it was sent.
     */
    @Test
    void aKeySplitAcrossChunksAtAnyPositionNeverReachesARenderer() throws Exception {
        String text = "key " + FAKE_KEY + " end";
        int keyStart = text.indexOf(FAKE_KEY);
        int keyEnd = keyStart + FAKE_KEY.length();
        Map<String, List<String>> messages = new java.util.LinkedHashMap<>();
        for (int first = 1; first < text.length(); first++) {
            messages.put("two-" + first, List.of(text.substring(0, first), text.substring(first)));
        }
        for (int first = keyStart + 1; first < keyEnd; first++) {
            for (int second = first + 1; second < keyEnd; second++) {
                messages.put("three-" + first + "-" + second, List.of(text.substring(0, first),
                        text.substring(first, second), text.substring(second)));
            }
        }
        List<Object> steps = new ArrayList<>();
        messages.forEach((messageId, chunks) -> chunks.forEach(chunk -> steps.add(Map.of("update", Map.of(
                "sessionUpdate", "agent_message_chunk", "messageId", messageId,
                "content", Map.of("type", "text", "text", chunk))))));
        Conversation agent = openAgentWith(json.writeValueAsString(Map.of("extends", "happy", "prompts",
                List.of(Map.of("stopReason", "end_turn", "steps", steps)))));
        agent.command("startAgent");
        agent.status("ready");

        agent.command("sendPrompt", "text", "Split the key");
        agent.event("runEnded");

        // Any 6 characters of the key ("mock" alone is in the mock's own names, such as "2.2.2-mock").
        List<String> pieces = new ArrayList<>();
        for (int at = 0; at + 6 <= FAKE_KEY.length(); at++) {
            pieces.add(FAKE_KEY.substring(at, at + 6));
        }
        for (String frame : agent.renderer.texts()) {
            assertThat(pieces).as("a piece of the key in %s", frame).noneMatch(frame::contains);
        }
        Map<String, String> live = new java.util.LinkedHashMap<>();
        agent.events("agentMessageChunk").forEach(event -> live.merge(text(event.get("data"), "messageId"),
                event.get("data").get("text").asString(), String::concat));
        assertThat(live.keySet()).containsAll(messages.keySet());
        messages.keySet().forEach(messageId -> assertThat(live.get(messageId)).as(messageId).isEqualTo("key **** end"));
        assertThat(agent.events("agentMessageChunk").stream().map(event -> event.get("seq").asLong()).toList())
                .isSorted();

        JsonNode snapshot = agent.reconnectSnapshot();
        stream(snapshot.get("recentEvents")).filter(event -> event.get("kind").asString().equals("agentMessageChunk"))
                .filter(event -> messages.containsKey(text(event.get("data"), "messageId")))
                .forEach(event -> assertThat(event.get("data").get("text").asString()).as("the tail is the live text")
                        .isEqualTo(live.get(text(event.get("data"), "messageId"))));
        agent.assertContract();
    }

    /**
     * ADR 0005 decision 11: codex-acp sends a command's output in pieces, one per tool call update
     * ({@code _meta.terminal_output.data}). A key split across two pieces at every position, and across
     * three at every pair of positions inside it, never shows in any frame; each command's output, joined
     * over its events, is its whole output masked. The last piece comes with the command's end in some
     * of them (it carries the held rest itself) and in its own update in the others.
     */
    @Test
    void aKeySplitAcrossCommandOutputAtAnyPositionNeverReachesARenderer() throws Exception {
        String text = "key " + FAKE_KEY + " end";
        int keyStart = text.indexOf(FAKE_KEY);
        int keyEnd = keyStart + FAKE_KEY.length();
        Map<String, List<String>> commands = new java.util.LinkedHashMap<>();
        for (int first = 1; first < text.length(); first++) {
            commands.put("two-" + first, List.of(text.substring(0, first), text.substring(first)));
        }
        for (int first = keyStart + 1; first < keyEnd; first++) {
            for (int second = first + 1; second < keyEnd; second++) {
                commands.put("three-" + first + "-" + second, List.of(text.substring(0, first),
                        text.substring(first, second), text.substring(second)));
            }
        }
        List<Object> steps = new ArrayList<>();
        commands.forEach((toolCallId, pieces) -> {
            steps.add(Map.of("update", Map.of("sessionUpdate", "tool_call", "toolCallId", toolCallId,
                    "title", "Run cat .env", "kind", "execute", "status", "in_progress")));
            boolean lastWithTheEnd = pieces.size() == 3;
            for (int i = 0; i < pieces.size(); i++) {
                Map<String, Object> update = new java.util.LinkedHashMap<>(Map.of("sessionUpdate", "tool_call_update",
                        "toolCallId", toolCallId));
                Map<String, Object> meta = new java.util.LinkedHashMap<>();
                meta.put("terminal_output", Map.of("data", pieces.get(i), "terminal_id", toolCallId));
                if (lastWithTheEnd && i == pieces.size() - 1) {
                    update.put("status", "completed");
                    meta.put("terminal_exit", Map.of("exit_code", 0, "terminal_id", toolCallId));
                }
                update.put("_meta", meta);
                steps.add(Map.of("update", update));
            }
            if (!lastWithTheEnd) {
                steps.add(Map.of("update", Map.of("sessionUpdate", "tool_call_update", "toolCallId", toolCallId,
                        "status", "completed", "_meta", Map.of("terminal_exit", Map.of("exit_code", 0,
                                "terminal_id", toolCallId)))));
            }
        });
        Conversation agent = openAgentWith(json.writeValueAsString(Map.of("extends", "happy", "prompts",
                List.of(Map.of("stopReason", "end_turn", "steps", steps)))));
        agent.command("startAgent");
        agent.status("ready");

        agent.command("sendPrompt", "text", "Read the environment");
        agent.event("runEnded");

        assertNoPieceOfTheKey(agent.renderer.texts());
        commands.keySet().forEach(toolCallId -> assertThat(agent.terminalOutput(toolCallId)).as(toolCallId)
                .isEqualTo("key **** end"));
        assertThat(agent.lastToolCall("two-1").get("status").asString()).isEqualTo("completed");
        assertThat(agent.events().stream().map(event -> event.get("seq").asLong()).toList()).isSorted();
        agent.assertContract();
    }

    /**
     * The mock's {@code terminal-output} scenario, shaped like codex-acp's reports: commands whose output
     * pieces interleave with another command's and with a thought, and output that comes with the end of
     * its command. Each command's output joins to its whole output masked, and the reconnect snapshot
     * shows no more of the key than the live frames.
     */
    @Test
    void commandOutputIsMaskedAcrossThePiecesOfItsTerminal() throws Exception {
        Conversation agent = openAgentWith("{\"extends\":\"terminal-output\"}");
        agent.command("startAgent");
        agent.status("ready");

        agent.command("sendPrompt", "text", "Look around");
        agent.event("runEnded");

        assertNoPieceOfTheKey(agent.renderer.texts());
        assertThat(agent.terminalOutput("call-cat")).isEqualTo("notes\nthe key **** stays hidden.\n");
        assertThat(agent.terminalOutput("call-env")).isEqualTo("PATH=/usr/bin\nOPENAI_API_KEY=****\nDEBUG=1\n");
        assertThat(agent.terminalOutput("call-ls")).isEqualTo("README.md\nnotes.txt\n");
        assertThat(agent.terminalOutput("call-grep")).as("the held rest goes out with the end").isEqualTo(
                "./a.txt: " + FAKE_KEY.substring(0, 4));
        assertThat(agent.lastToolCall("call-env").get("vendorMeta").get("terminal_exit").get("exit_code").asInt())
                .isZero();
        assertThat(agent.lastToolCall("call-grep").get("status").asString()).isEqualTo("failed");
        assertNoPieceOfTheKey(List.of(agent.reconnectSnapshot().toString()));
        agent.assertContract();
    }

    /** No 6 characters of the mock's key ("mock" alone is in its own names, such as "2.2.2-mock"). */
    private static void assertNoPieceOfTheKey(List<String> frames) {
        List<String> pieces = new ArrayList<>();
        for (int at = 0; at + 6 <= FAKE_KEY.length(); at++) {
            pieces.add(FAKE_KEY.substring(at, at + 6));
        }
        for (String frame : frames) {
            assertThat(pieces).as("a piece of the key in %s", frame).noneMatch(frame::contains);
        }
    }

    // ── checkConnection (ADR 0004 decision 6) ──────────────────────────

    @Test
    void checkConnectionRunsInitializeOnlyAndNeverMovesTheStatus() throws Exception {
        Conversation agent = openAgent("happy");

        agent.command("checkConnection");
        JsonNode checked = agent.frame("connectionChecked");

        assertThat(checked.get("agentId").asString()).isEqualTo(agent.agentId);
        assertThat(checked.get("readiness").asString()).isEqualTo("configured");
        assertThat(checked.get("authMethods")).extracting(method -> method.get("id").asString())
                .as("api-key is hidden (SCRUM-65)").containsExactly("chat-gpt");
        assertThat(checked.get("authStatus").get("label").asString()).isEqualTo("ChatGPT Plus");
        assertThat(checked.has("error")).isFalse();
        assertThat(agent.events()).as("a check emits no agent events").isEmpty();

        MockAcpAgent.useScenario(SCENARIO, "auth-required");
        agent.command("checkConnection");
        JsonNode notLoggedIn = agent.frame("connectionChecked");
        assertThat(notLoggedIn.get("readiness").asString()).isEqualTo("auth_required");
        assertThat(notLoggedIn.get("authStatus").get("kind").asString()).isEqualTo("none");
        assertThat(agent.frame("agentsUpdated").get("agents").get(0).get("readiness").asString())
                .isEqualTo("auth_required");

        MockAcpAgent.useScenario(SCENARIO, "exit-at-start");
        agent.command("checkConnection");
        JsonNode failed = agent.frame("connectionChecked");
        assertThat(failed.get("error").get("code").asString()).isEqualTo("ADAPTER_START_FAILED");
        assertThat(failed.get("error").get("stage").asString()).isEqualTo("spawn");

        MockAcpAgent.useScenario(SCENARIO, "happy");
        agent.command("startAgent");
        agent.status("ready");
        agent.command("checkConnection");
        assertThat(agent.frame("commandRejected").get("reason").asString()).isEqualTo("INVALID_STATE");
        assertThat(agent.reconnectSnapshot().get("agent").get("readiness").asString())
                .as("the sign-in status of the start replaced the check's").isEqualTo("configured");
        agent.assertContract();
    }

    /**
     * ADR 0005 decision 4: an adapter that answers initialize and then exits or breaks stdout while the
     * check waits for its status is no healthy connection; the agent's status still never moves.
     */
    @ParameterizedTest
    @CsvSource({
        "crash-after-initialize, PROCESS_EXITED, code 5",
        "malformed-after-initialize, PROTOCOL_ERROR, not an ACP JSON-RPC message"
    })
    void aCheckWhoseAdapterBreaksAfterInitializeReportsTheError(String scenario, String code, String message)
            throws Exception {
        Conversation agent = openAgent(scenario);

        agent.command("checkConnection");
        JsonNode checked = agent.frame("connectionChecked");

        assertThat(checked.get("error").get("code").asString()).isEqualTo(code);
        assertThat(checked.get("error").get("stage").asString()).isEqualTo("initialize");
        assertThat(checked.get("error").get("message").asString()).contains(message);
        assertThat(checked.get("readiness").asString()).isEqualTo("configured");
        assertThat(agent.events()).as("a check emits no agent events").isEmpty();
        agent.assertContract();
    }

    /**
     * D9, ADR 0005 decision 2: what a check or a start observes about the adapter is the agent's
     * readiness, so connectionChecked and agentsUpdated (and the snapshot) never disagree.
     */
    @Test
    void anAdapterWithoutTheSignInMakesTheAgentUnsupportedUntilOneOffersIt() throws Exception {
        Conversation agent = openAgent("no-chatgpt");

        agent.command("checkConnection");
        JsonNode checked = agent.frame("connectionChecked");
        assertThat(checked.get("readiness").asString()).isEqualTo("unsupported");
        assertThat(checked.get("error").get("code").asString()).isEqualTo("AUTH_FAILED");
        assertThat(agent.frame("agentsUpdated").get("agents").get(0).get("readiness").asString())
                .isEqualTo("unsupported");
        assertThat(agent.reconnectSnapshot().get("agent").get("readiness").asString()).isEqualTo("unsupported");

        MockAcpAgent.useScenario(SCENARIO, "happy");
        agent.command("checkConnection");
        assertThat(agent.frame("connectionChecked").get("readiness").asString()).isEqualTo("configured");
        assertThat(agent.frame("agentsUpdated").get("agents").get(0).get("readiness").asString())
                .isEqualTo("configured");

        MockAcpAgent.useScenario(SCENARIO, "no-chatgpt");
        agent.command("startAgent");
        agent.status("error");
        assertThat(agent.frames("agentsUpdated").getLast().get("agents").get(0).get("readiness").asString())
                .as("a start that finds no sign-in says so too").isEqualTo("unsupported");
        agent.assertContract();
    }

    /**
     * D17: one project is open. Another opens only while no agent of the current one is active, so a
     * running adapter is never left without a way to stop it.
     */
    @Test
    void anotherProjectOpensOnlyWhileNoAgentIsActive() throws Exception {
        Conversation agent = openAgent("long-run");
        agent.command("startAgent");
        agent.status("ready");
        agent.command("sendPrompt", "text", "Run the tests");
        agent.event("agentMessageChunk");
        Path other = newProjectFolder();

        agent.send(json.writeValueAsString(Map.of("type", "openProject", "path", other.toString())));
        JsonNode rejected = agent.frame("commandRejected");
        assertThat(rejected.get("command").asString()).isEqualTo("openProject");
        assertThat(rejected.get("reason").asString()).isEqualTo("INVALID_STATE");

        // The same project again is fine, and the agent goes on.
        agent.send(json.writeValueAsString(Map.of("type", "openProject", "path", agent.projectPath)));
        assertThat(agent.frame("projectOpened").get("project").get("projectId").asString()).isEqualTo(agent.projectId);
        assertThat(agent.statuses().getLast()).isEqualTo("running");
        assertThat(agent.reconnectSnapshot().get("status").asString()).isEqualTo("running");

        agent.command("stopAgent");
        agent.status("offline");
        agent.send(json.writeValueAsString(Map.of("type", "openProject", "path", other.toString())));
        assertThat(agent.frame("projectOpened").get("project").get("projectId").asString())
                .isNotEqualTo(agent.projectId);
        agent.agentId = null;
        agent.assertContract();
    }

    // ── D11, D12: how the adapter is started ──────────────────────────

    @Test
    void theAdapterGetsOnlyAllowlistedVariablesAndRunsInTheProject() throws Exception {
        Conversation agent = openAgent("env");
        agent.command("startAgent");
        agent.status("ready");

        agent.command("sendPrompt", "text", "Show the environment");
        agent.event("runEnded");

        String envLine = agent.events("agentMessageChunk").stream()
                .map(event -> event.get("data").get("text").asString())
                .filter(text -> text.startsWith("env: "))
                .findFirst().orElseThrow();
        Set<String> names = Stream.of(envLine.substring("env: ".length()).split(","))
                .map(name -> name.toUpperCase(Locale.ROOT))
                .collect(Collectors.toSet());
        assertThat(names).contains("PATH");
        assertThat(names).allSatisfy(name -> assertThat(isAllowlisted(name)).as(name).isTrue());
        assertThat(names).noneMatch(name -> name.startsWith("AGENTS_ROOM_"));
        assertThat(names).doesNotContain("OPENAI_API_KEY", "CODEX_API_KEY", "NO_BROWSER", "APP_SERVER_LOGS");
        agent.assertContract();
    }

    /**
     * ADR 0003 decision 12: on a plain node (a runtime the shell did not start, such as the dev jar run)
     * the launch adds neither {@code ELECTRON_RUN_AS_NODE} nor the preload, and every process still runs.
     * The launch on the shell's Electron is {@link ElectronAsNodeIntegrationTests}.
     */
    @Test
    void onAPlainNodeTheLaunchAddsNoPreloadAndNoProcessSeesElectronRunAsNode() throws Exception {
        Conversation agent = openAgent("probe-run-as-node");
        agent.command("startAgent");
        agent.status("ready");

        agent.command("sendPrompt", "text", "Probe the environment");
        agent.event("runEnded");

        JsonNode report = agent.lastToolCall("call-probe-run-as-node").get("rawOutput");
        assertThat(report.get("adapter").get("preloaded").asBoolean()).isFalse();
        assertThat(report.get("adapter").get("sees").asBoolean()).isFalse();
        assertThat(report.get("adapter").get("commandSees").asBoolean()).isFalse();
        assertThat(report.get("codex").get("answered").asBoolean()).as("the adapter's own node child runs").isTrue();
        assertThat(report.get("codex").get("preloaded").asBoolean()).isFalse();
        assertThat(report.get("codex").get("sees").asBoolean()).isFalse();
        assertThat(report.get("codex").get("commandSees").asBoolean()).isFalse();
        assertThat(report.get("execFileChild").get("answered").asBoolean()).isTrue();
        assertThat(report.get("execFileChild").get("preloaded").asBoolean()).isFalse();
        agent.assertContract();
    }

    // ── Helpers ───────────────────────────────────────────────────────

    private static boolean isAllowlisted(String name) {
        return AdapterEnvironment.isPassed(name);
    }

    /** Writes the scenario, opens a fresh project (and with it a fresh agent) and connects a renderer. */
    private Conversation openAgent(String scenario) throws Exception {
        return openAgentWith("{\"extends\":\"" + scenario + "\"}");
    }

    /** {@link #openAgent} with a scenario JSON (the mock's keys; "extends" starts from a built-in one). */
    private Conversation openAgentWith(String scenarioJson) throws Exception {
        MockAcpAgent.useScenarioJson(SCENARIO, scenarioJson);
        Path folder = newProjectFolder();
        Conversation conversation = new Conversation(TestRenderer.connect(uri()));
        open = conversation;
        conversation.frame("snapshot");
        conversation.send(json.writeValueAsString(Map.of("type", "openProject", "path", folder.toString())));
        JsonNode project = conversation.frame("projectOpened").get("project");
        conversation.projectId = project.get("projectId").asString();
        conversation.projectPath = project.get("path").asString();
        conversation.agentId = conversation.frame("agentsUpdated").get("agents").get(0).get("agentId").asString();
        return conversation;
    }

    private static Path newProjectFolder() throws IOException {
        Path folder = Files.createDirectories(PROJECTS.resolve("project-" + (++projectCount)));
        Files.writeString(folder.resolve("README.md"), "# demo", StandardCharsets.UTF_8);
        return folder;
    }

    private String respond(String permissionId, String optionId) {
        return json.writeValueAsString(
                Map.of("type", "respondPermission", "permissionId", permissionId, "optionId", optionId));
    }

    private URI uri() {
        return URI.create("ws://127.0.0.1:" + port + "/ws?token=" + TOKEN);
    }

    private static long adapterPid(CapturedOutput output) {
        Matcher matcher = ADAPTER_PID.matcher(output.getAll());
        long pid = -1;
        while (matcher.find()) {
            pid = Long.parseLong(matcher.group(1));
        }
        assertThat(pid).as("the runtime logged the adapter pid").isPositive();
        return pid;
    }

    private static void awaitGone(long pid) throws InterruptedException {
        long deadline = System.nanoTime() + WAIT.toNanos();
        while (ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false)) {
            if (System.nanoTime() > deadline) {
                fail("process " + pid + " is still alive");
            }
            Thread.sleep(50);
        }
    }

    private static Stream<JsonNode> stream(JsonNode array) {
        return array.valueStream();
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asString();
    }

    private static Path createTempDirectory() {
        try {
            return Files.createTempDirectory("agents-room-acp-it");
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** One renderer's view of one agent: every frame it got, and helpers to wait for the next ones. */
    private final class Conversation {

        final TestRenderer renderer;
        final List<JsonNode> seen = new ArrayList<>();
        String projectId;
        String projectPath;
        String agentId;

        Conversation(TestRenderer renderer) {
            this.renderer = renderer;
        }

        void send(String text) {
            renderer.send(text);
        }

        /** An agent command for this agent, with extra string fields as name, value, ... */
        void command(String type, String... fields) {
            ObjectNode command = json.createObjectNode().put("type", type).put("agentId", agentId);
            for (int i = 0; i < fields.length; i += 2) {
                command.put(fields[i], fields[i + 1]);
            }
            send(command.toString());
        }

        /** The next frame of this type, reading on until it comes. */
        JsonNode frame(String type) throws InterruptedException {
            return until(frame -> frame.get("type").asString().equals(type), "a " + type + " frame");
        }

        /** The next event of this kind. */
        JsonNode event(String kind) throws InterruptedException {
            return event(kind, event -> true);
        }

        JsonNode event(String kind, Predicate<JsonNode> matches) throws InterruptedException {
            JsonNode frame = until(f -> f.get("type").asString().equals("agentEvent")
                    && f.get("event").get("kind").asString().equals(kind) && matches.test(f.get("event")),
                    "a " + kind + " event");
            return frame.get("event");
        }

        /** Waits for the agent to reach this status. */
        void status(String status) throws InterruptedException {
            event("agentStatusChanged", event -> event.get("data").get("status").asString().equals(status));
        }

        boolean idle() {
            List<String> statuses = statuses();
            return statuses.isEmpty() || Set.of("offline", "error").contains(statuses.getLast());
        }

        JsonNode until(Predicate<JsonNode> matches, String what) throws InterruptedException {
            long deadline = System.nanoTime() + WAIT.toNanos();
            while (true) {
                long left = deadline - System.nanoTime();
                JsonNode frame = left > 0 ? renderer.poll(Duration.ofNanos(left)) : null;
                if (frame == null) {
                    fail("No " + what + " within " + WAIT + "; got " + kinds());
                }
                seen.add(frame);
                if (matches.test(frame)) {
                    return frame;
                }
            }
        }

        List<JsonNode> frames(String type) {
            return seen.stream().filter(frame -> frame.get("type").asString().equals(type)).toList();
        }

        List<JsonNode> events() {
            return frames("agentEvent").stream().map(frame -> frame.get("event")).toList();
        }

        List<JsonNode> events(String kind) {
            return events().stream().filter(event -> event.get("kind").asString().equals(kind)).toList();
        }

        JsonNode first(String kind) {
            return events(kind).stream().findFirst().orElseThrow(() -> new AssertionError("no " + kind + " in " + kinds()));
        }

        List<String> kinds() {
            return events().stream().map(event -> event.get("kind").asString()).toList();
        }

        List<String> statuses() {
            return events("agentStatusChanged").stream().map(event -> event.get("data").get("status").asString())
                    .toList();
        }

        List<String> stages() {
            return events("agentStarting").stream().map(event -> event.get("data").get("stage").asString()).toList();
        }

        /** When the runtime entered this start stage the first time (the event's receivedAt). */
        long stageReceivedAt(String stage) {
            return events("agentStarting").stream()
                    .filter(event -> event.get("data").get("stage").asString().equals(stage))
                    .findFirst().orElseThrow().get("receivedAt").asLong();
        }

        List<JsonNode> eventsBetween(String fromKind, String toKind) {
            List<JsonNode> events = events();
            List<String> kinds = kinds();
            return events.subList(kinds.indexOf(fromKind), kinds.indexOf(toKind) + 1);
        }

        /** The merged state of the latest toolCall or toolCallUpdate of this id. */
        JsonNode lastToolCall(String toolCallId) {
            JsonNode last = null;
            for (JsonNode event : events()) {
                String kind = event.get("kind").asString();
                if ((kind.equals("toolCall") || kind.equals("toolCallUpdate"))
                        && event.get("data").get("toolCall").get("toolCallId").asString().equals(toolCallId)) {
                    last = event.get("data").get("toolCall");
                }
            }
            assertThat(last).as("tool call %s", toolCallId).isNotNull();
            return last;
        }

        /** A command's output as its events carried it: their {@code vendorMeta.terminal_output.data}, joined. */
        String terminalOutput(String toolCallId) {
            StringBuilder output = new StringBuilder();
            for (JsonNode event : events()) {
                String kind = event.get("kind").asString();
                if ((kind.equals("toolCall") || kind.equals("toolCallUpdate"))
                        && event.get("data").get("toolCall").get("toolCallId").asString().equals(toolCallId)) {
                    JsonNode meta = event.get("data").get("toolCall").get("vendorMeta");
                    if (meta != null && meta.has("terminal_output")) {
                        output.append(meta.get("terminal_output").get("data").asString());
                    }
                }
            }
            return output.toString();
        }

        /** This agent in the snapshot a newly connected renderer gets. */
        JsonNode reconnectSnapshot() throws Exception {
            TestRenderer other = TestRenderer.connect(uri());
            try {
                JsonNode snapshot = other.next();
                String text = other.texts().getFirst();
                assertThat(snapshot.get("type").asString()).isEqualTo("snapshot");
                Optional<JsonNode> agent = stream(snapshot.get("agents"))
                        .filter(entry -> entry.get("agent").get("agentId").asString().equals(agentId))
                        .findFirst();
                assertThat(agent).as("the agent in the snapshot (%s)", text).isPresent();
                List<Verdict> verdicts = ContractSchema.validateServerMessages(other.texts().subList(0, 1));
                assertThat(verdicts.getFirst().isAgentsRoomMessage()).as("%s", verdicts.getFirst().errors()).isTrue();
                return agent.get();
            } finally {
                other.close();
            }
        }

        /**
         * Every frame this renderer got is an agents-room frame of core/asyncapi.yaml, and none carries
         * the mock's fake key whole (it is on the mock's stderr and in some of its messages).
         */
        void assertContract() {
            List<String> texts = renderer.texts();
            assertThat(String.join("\n", texts)).as("no frame carries the whole key (masking)").doesNotContain(FAKE_KEY);
            List<Verdict> verdicts = ContractSchema.validateServerMessages(texts);
            for (int i = 0; i < texts.size(); i++) {
                assertThat(verdicts.get(i).isAgentsRoomMessage()).as("%s %s", texts.get(i), verdicts.get(i).errors())
                        .isTrue();
            }
        }
    }
}
