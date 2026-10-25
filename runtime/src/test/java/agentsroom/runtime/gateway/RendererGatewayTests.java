package agentsroom.runtime.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import agentsroom.runtime.shell.ShellLinkConfiguration;
import agentsroom.runtime.testsupport.ContractSchema;
import agentsroom.runtime.testsupport.ContractSchema.Verdict;
import io.netty.handler.codec.http.websocketx.WebSocketClientHandshakeException;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.reactive.socket.WebSocketMessage;
import org.springframework.web.reactive.socket.WebSocketSession;
import org.springframework.web.reactive.socket.client.ReactorNettyWebSocketClient;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.WebsocketClientSpec;
import reactor.test.StepVerifier;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** The renderer WebSocket end to end: the gate, the snapshot on connect, commands and broadcasts. */
@SpringBootTest(
        webEnvironment = WebEnvironment.RANDOM_PORT,
        properties = {
            "AGENTS_ROOM_TOKEN=" + RendererGatewayTests.TOKEN,
            "AGENTS_ROOM_ALLOWED_ORIGINS=" + RendererGatewayTests.APP_ORIGIN + ", " + RendererGatewayTests.DEV_ORIGIN,
            ShellLinkConfiguration.ENABLED_PROPERTY + "=false"
        })
class RendererGatewayTests {

    /** 43 characters, the shape of a real token (32 bytes in base64url). */
    static final String TOKEN = "gateway-test-token-xxxxxxxxxxxxxxxxxxxxxxxx";
    static final String APP_ORIGIN = "app://bundle";
    static final String DEV_ORIGIN = "http://localhost:5173";

    private static final Duration TIMEOUT = Duration.ofSeconds(15);
    private static final int CLIENT_MAX_FRAME_BYTES = 1024 * 1024;
    private static final Path DATA_DIR = createTempDirectory();

    @DynamicPropertySource
    static void dataDirectory(DynamicPropertyRegistry registry) {
        registry.add("AGENTS_ROOM_DATA_DIR", DATA_DIR::toString);
    }

    @AfterAll
    static void deleteDataDirectory() throws IOException {
        try (Stream<Path> paths = Files.walk(DATA_DIR)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
        }
    }

    private final ReactorNettyWebSocketClient client = new ReactorNettyWebSocketClient(
            reactor.netty.http.client.HttpClient.create(),
            () -> WebsocketClientSpec.builder().maxFramePayloadLength(CLIENT_MAX_FRAME_BYTES));
    private final JsonMapper json = JsonMapper.builder().build();

    @LocalServerPort
    int port;

    @Autowired
    RendererGateway gateway;

    @TempDir
    Path projects;

    @Test
    void acceptsTheTokenWithTheAppOriginAndSendsTheSnapshotFirst() {
        List<JsonNode> frames = converse(origin(APP_ORIGIN), List.of(), 1);

        JsonNode snapshot = frames.getFirst();
        assertThat(snapshot.get("type").asString()).isEqualTo("snapshot");
        assertThat(snapshot.has("currentProjectId")).isTrue();
        assertThat(snapshot.get("recentProjects").isArray()).isTrue();
        assertThat(snapshot.get("agents").isArray()).isTrue();
    }

    @Test
    void acceptsTheDevOrigin() {
        assertThat(converse(origin(DEV_ORIGIN), List.of(), 1).getFirst().get("type").asString()).isEqualTo("snapshot");
    }

    @Test
    void acceptsAClientThatSendsNoOrigin() throws Exception {
        // The JDK client sends no Origin header, like Node or curl.
        CompletableFuture<String> first = new CompletableFuture<>();
        WebSocket socket = HttpClient.newHttpClient()
                .newWebSocketBuilder()
                .buildAsync(uri(TOKEN), new WebSocket.Listener() {
                    @Override
                    public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
                        first.complete(data.toString());
                        return null;
                    }
                })
                .get(TIMEOUT.toSeconds(), TimeUnit.SECONDS);

        assertThat(json.readTree(first.get(TIMEOUT.toSeconds(), TimeUnit.SECONDS)).get("type").asString())
                .isEqualTo("snapshot");
        socket.sendClose(WebSocket.NORMAL_CLOSURE, "done").get(TIMEOUT.toSeconds(), TimeUnit.SECONDS);
    }

    @Test
    void refusesAMissingTokenWith401() {
        assertHandshakeStatus(URI.create("ws://127.0.0.1:" + port + "/ws"), origin(APP_ORIGIN), 401);
    }

    @Test
    void refusesAWrongTokenWith401() {
        assertHandshakeStatus(uri("wrong-" + TOKEN), origin(APP_ORIGIN), 401);
        assertHandshakeStatus(uri(TOKEN.substring(1)), origin(APP_ORIGIN), 401);
    }

    @Test
    void refusesAForeignOriginWith403() {
        assertHandshakeStatus(uri(TOKEN), origin("http://evil.example"), 403);
        assertHandshakeStatus(uri(TOKEN), origin("null"), 403);
    }

    @Test
    void opensAProjectAndTellsEveryRenderer() throws IOException {
        Path folder = Files.createDirectories(projects.resolve("demo"));
        Files.createDirectories(folder.resolve("src"));
        Files.writeString(folder.resolve("src/App.java"), "class App {}", StandardCharsets.UTF_8);

        List<JsonNode> frames = converse(origin(APP_ORIGIN), List.of(openProject(folder.toString())), 3);

        assertThat(frames).extracting(frame -> frame.get("type").asString())
                .containsExactly("snapshot", "projectOpened", "agentsUpdated");
        JsonNode project = frames.get(1).get("project");
        assertThat(project.get("name").asString()).isEqualTo("demo");
        assertThat(project.get("path").asString()).isEqualTo(folder.toRealPath().toString());
        assertThat(project.get("filesTruncated").asBoolean()).isFalse();
        assertThat(project.get("files").valueStream().map(file -> file.get("relativePath").asString()).toList())
                .containsExactly("src", "src/App.java");
        JsonNode agent = frames.get(2).get("agents").get(0);
        assertThat(agent.get("name").asString()).isEqualTo("Codex");
        assertThat(agent.get("harnessId").asString()).isEqualTo("codex");
        assertThat(agent.get("projectId").asString()).isEqualTo(project.get("projectId").asString());
        assertThat(agent.get("modelId").isNull()).isTrue();
        assertThat(agent.get("readiness").asString()).isEqualTo("configured");
        assertThat(agent.get("reportsToolDetails").asBoolean()).isTrue();
        assertThat(agent.get("reportsSubagentSessions").asBoolean()).isFalse();

        JsonNode snapshot = converse(origin(APP_ORIGIN), List.of(), 1).getFirst();
        assertThat(snapshot.get("currentProjectId").asString()).isEqualTo(project.get("projectId").asString());
        assertThat(snapshot.get("project").get("path").asString()).isEqualTo(folder.toRealPath().toString());
        JsonNode agentSnapshot = snapshot.get("agents").get(0);
        assertThat(agentSnapshot.get("status").asString()).isEqualTo("offline");
        assertThat(agentSnapshot.get("agent")).isEqualTo(agent);
        assertThat(agentSnapshot.get("lastSeq").asLong()).isZero();
        assertThat(agentSnapshot.get("recentEvents").isEmpty()).isTrue();
    }

    @Test
    void reportsEachValidationFailureToTheSender() throws IOException {
        Path file = Files.writeString(projects.resolve("notes.txt"), "x", StandardCharsets.UTF_8);
        String missing = projects.resolve("missing").toString();

        List<JsonNode> frames = converse(origin(APP_ORIGIN), List.of(
                openProject("relative/dir"), openProject(missing), openProject(file.toString())), 4);

        assertThat(frames.subList(1, 4)).allSatisfy(frame -> assertThat(frame.get("type").asString())
                .isEqualTo("projectOpenFailed"));
        assertThat(frames.subList(1, 4)).extracting(frame -> frame.get("code").asString())
                .containsExactly("PATH_NOT_ABSOLUTE", "PATH_NOT_FOUND", "PATH_NOT_DIRECTORY");
        assertThat(frames.get(2).get("path").asString()).isEqualTo(missing);
    }

    @Test
    void rejectsBadFramesAndKeepsTheConnection() {
        List<String> badFrames = List.of(
                "not json",
                "{\"type\":\"bogus\"}",
                "{\"type\":\"webviewReady\"}",
                "{\"type\":\"startAgent\",\"agentId\":\"a\"}",
                "{\"type\":\"openProject\"}",
                "{\"type\":\"openProject\",\"path\":\"C:\\\\x\",\"force\":true}");

        List<JsonNode> frames = converse(origin(APP_ORIGIN), badFrames, 7);

        List<JsonNode> replies = frames.subList(1, 7);
        assertThat(replies).allSatisfy(frame -> assertThat(frame.get("type").asString())
                .isEqualTo("commandRejected"));
        assertThat(replies).extracting(frame -> frame.get("command").asString())
                .containsExactly("unknown", "bogus", "webviewReady", "startAgent", "openProject", "openProject");
        assertThat(replies).extracting(frame -> frame.get("reason").asString())
                .containsExactly("INVALID_MESSAGE", "INVALID_MESSAGE", "INVALID_MESSAGE", "UNKNOWN_AGENT",
                        "INVALID_MESSAGE", "INVALID_MESSAGE");
        assertThat(replies.get(3).get("agentId").asString()).isEqualTo("a");
    }

    /**
     * The commands this runtime does not handle yet still get their ids checked, and every frame of the
     * conversation is an agents-room frame of {@code core/asyncapi.yaml}.
     */
    @Test
    void checksTheIdsOfTheCommandsItDoesNotHandleYetAndAnswersInContractFrames() throws Exception {
        Path folder = Files.createDirectories(projects.resolve("ids"));
        TestRenderer renderer = TestRenderer.connect(uri(TOKEN));
        renderer.next();
        renderer.send(openProject(folder.toString()));
        String projectId = renderer.next().get("project").get("projectId").asString();
        String agentId = renderer.next().get("agents").get(0).get("agentId").asString();
        String unknown = UUID.randomUUID().toString();
        String path = folder.toString();

        record Case(String command, String reason, String agentId, String projectId, String permissionId) {
        }
        List<Case> cases = List.of(
                new Case(command("checkConnection", "agentId", agentId), "NOT_IMPLEMENTED", agentId, null, null),
                new Case(command("startAgent", "agentId", agentId), "NOT_IMPLEMENTED", agentId, null, null),
                new Case(command("stopAgent", "agentId", agentId), "NOT_IMPLEMENTED", agentId, null, null),
                new Case(command("sendPrompt", "agentId", agentId, "text", "hi"), "NOT_IMPLEMENTED", agentId, null,
                        null),
                new Case(command("cancelRun", "agentId", agentId, "runId", unknown), "NOT_IMPLEMENTED", agentId, null,
                        null),
                new Case(command("respondPermission", "permissionId", unknown, "optionId", "approved"),
                        "NOT_IMPLEMENTED", null, null, unknown),
                new Case(command("relocateProject", "projectId", projectId, "path", path), "NOT_IMPLEMENTED", null,
                        projectId, null),
                new Case(saveAgentConfig(projectId, null), "NOT_IMPLEMENTED", null, projectId, null),
                new Case(saveAgentConfig(projectId, agentId), "NOT_IMPLEMENTED", agentId, projectId, null),
                new Case(command("startAgent", "agentId", unknown), "UNKNOWN_AGENT", unknown, null, null),
                new Case(command("sendPrompt", "agentId", unknown, "text", "hi"), "UNKNOWN_AGENT", unknown, null, null),
                new Case(command("relocateProject", "projectId", unknown, "path", path), "UNKNOWN_PROJECT", null,
                        unknown, null),
                new Case(saveAgentConfig(unknown, null), "UNKNOWN_PROJECT", null, unknown, null),
                new Case(saveAgentConfig(projectId, unknown), "UNKNOWN_AGENT", unknown, projectId, null),
                new Case(command("sendPrompt", "agentId", agentId), "INVALID_MESSAGE", agentId, null, null));

        for (Case expected : cases) {
            renderer.send(expected.command());
            JsonNode reply = renderer.next();

            String type = json.readTree(expected.command()).get("type").asString();
            assertThat(reply.get("type").asString()).as(expected.command()).isEqualTo("commandRejected");
            assertThat(reply.get("command").asString()).isEqualTo(type);
            assertThat(reply.get("reason").asString()).as(expected.command()).isEqualTo(expected.reason());
            assertThat(text(reply, "agentId")).as("agentId of %s", expected.command()).isEqualTo(expected.agentId());
            assertThat(text(reply, "projectId")).as("projectId of %s", expected.command())
                    .isEqualTo(expected.projectId());
            assertThat(text(reply, "permissionId")).isEqualTo(expected.permissionId());
        }
        renderer.close();

        List<String> received = renderer.texts();
        assertThat(received).hasSize(3 + cases.size());
        List<Verdict> verdicts = ContractSchema.validateServerMessages(received);
        for (int i = 0; i < received.size(); i++) {
            assertThat(verdicts.get(i).isAgentsRoomMessage()).as("%s %s", received.get(i), verdicts.get(i).errors())
                    .isTrue();
        }
    }

    @Test
    void broadcastsToEveryConnectedRenderer() throws InterruptedException {
        List<String> first = new CopyOnWriteArrayList<>();
        List<String> second = new CopyOnWriteArrayList<>();
        Disposable one = collect(first, 2).subscribe();
        Disposable two = collect(second, 2).subscribe();
        try {
            // A connection joins the broadcast set right after its snapshot is queued.
            awaitCondition(() -> first.size() == 1 && second.size() == 1);
            awaitCondition(() -> gateway.connectionCount() >= 2);

            gateway.broadcast(RendererFrames.agentsUpdated(List.of()));

            awaitCondition(() -> first.size() == 2 && second.size() == 2);
            assertThat(first.get(1)).isEqualTo("{\"type\":\"agentsUpdated\",\"agents\":[]}");
            assertThat(second.get(1)).isEqualTo(first.get(1));
        } finally {
            one.dispose();
            two.dispose();
        }
    }

    @Test
    void aRendererThatConnectsDuringChangesGetsItsSnapshotFirstAndNoHalfOfAChange() throws Exception {
        Path alpha = Files.createDirectories(projects.resolve("alpha"));
        Path beta = Files.createDirectories(projects.resolve("beta"));
        TestRenderer switcher = TestRenderer.connect(uri(TOKEN));
        switcher.next();
        // The current project flips with every command, so each change differs from the one before.
        int switches = 300;
        for (int i = 0; i < switches; i++) {
            switcher.send(openProject((i % 2 == 0 ? alpha : beta).toString()));
        }

        int connects = 0;
        long deadline = System.nanoTime() + TIMEOUT.toNanos();
        while (switcher.received() < 1 + 2 * switches && System.nanoTime() < deadline) {
            TestRenderer renderer = TestRenderer.connect(uri(TOKEN));
            JsonNode snapshot = renderer.next();
            JsonNode following = renderer.poll(Duration.ofMillis(20));
            renderer.close();
            connects++;

            assertThat(snapshot.get("type").asString()).as("first frame").isEqualTo("snapshot");
            if (following != null) {
                // A change is projectOpened + agentsUpdated; getting only the second half, or a change the
                // snapshot already shows, would mean the snapshot and the broadcast overlapped.
                assertThat(following.get("type").asString()).isEqualTo("projectOpened");
                assertThat(following.get("project").get("projectId").asString())
                        .isNotEqualTo(snapshot.get("currentProjectId").asString());
            }
        }
        switcher.close();

        assertThat(connects).as("renderers connected while the project kept changing").isGreaterThan(1);
    }

    @Test
    void acceptsFramesAboveTheNettyDefaultLimit() {
        String longPath = projects.resolve("x".repeat(100_000)).toString();

        List<JsonNode> frames = converse(origin(APP_ORIGIN), List.of(openProject(longPath)), 2);

        assertThat(frames.get(1).get("type").asString()).isEqualTo("projectOpenFailed");
    }

    private List<JsonNode> converse(HttpHeaders headers, List<String> outgoing, int expected) {
        List<String> received = new CopyOnWriteArrayList<>();
        client.execute(uri(TOKEN), headers, session -> {
                    Mono<Void> in = session.receive()
                            .map(WebSocketMessage::getPayloadAsText)
                            .doOnNext(received::add)
                            .take(expected)
                            .then();
                    Mono<Void> out = session.send(Flux.fromIterable(outgoing).map(session::textMessage));
                    return Mono.when(in, out);
                })
                .block(TIMEOUT);
        assertThat(received).hasSize(expected);
        return received.stream().map(json::readTree).toList();
    }

    private Mono<Void> collect(List<String> received, int expected) {
        return client.execute(uri(TOKEN), origin(APP_ORIGIN), session -> session.receive()
                .map(WebSocketMessage::getPayloadAsText)
                .doOnNext(received::add)
                .take(expected)
                .then());
    }

    private void assertHandshakeStatus(URI uri, HttpHeaders headers, int status) {
        StepVerifier.create(client.execute(uri, headers, WebSocketSession::close))
                .expectErrorSatisfies(error -> assertThat(handshakeStatus(error)).isEqualTo(status))
                .verify(TIMEOUT);
    }

    private static int handshakeStatus(Throwable error) {
        Throwable cause = error;
        while (cause != null && !(cause instanceof WebSocketClientHandshakeException)) {
            cause = cause.getCause();
        }
        assertThat(cause).as("handshake failure in %s", error).isNotNull();
        return ((WebSocketClientHandshakeException) cause).response().status().code();
    }

    private URI uri(String token) {
        return URI.create("ws://127.0.0.1:" + port + "/ws?token=" + token);
    }

    private static HttpHeaders origin(String origin) {
        // ReactorNettyWebSocketClient would otherwise send "Origin: http://127.0.0.1:<port>".
        HttpHeaders headers = new HttpHeaders();
        headers.setOrigin(origin);
        return headers;
    }

    private String openProject(String path) {
        return json.writeValueAsString(Map.of("type", "openProject", "path", path));
    }

    /** A command of {@code type} with string fields given as name, value, name, value... */
    private String command(String type, String... fields) {
        ObjectNode command = json.createObjectNode().put("type", type);
        for (int i = 0; i < fields.length; i += 2) {
            command.put(fields[i], fields[i + 1]);
        }
        return command.toString();
    }

    /** {@code saveAgentConfig} for the default Codex agent; without {@code agentId} it creates one. */
    private String saveAgentConfig(String projectId, String agentId) {
        ObjectNode command = json.createObjectNode().put("type", "saveAgentConfig");
        ObjectNode config = command.putObject("config");
        if (agentId != null) {
            config.put("agentId", agentId);
        }
        config.put("projectId", projectId)
                .put("name", "Codex")
                .put("harnessId", "codex")
                .put("providerId", "openai")
                .putNull("modelId");
        config.put("authMethodId", "chat-gpt");
        return command.toString();
    }

    /** The string field, or {@code null} when the frame left it out. */
    private static String text(JsonNode frame, String field) {
        JsonNode value = frame.get(field);
        return value == null ? null : value.asString();
    }

    private static void awaitCondition(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TIMEOUT.toNanos();
        while (!condition.getAsBoolean()) {
            assertThat(System.nanoTime()).as("condition within %s", TIMEOUT).isLessThan(deadline);
            Thread.sleep(20);
        }
    }

    /** A renderer on the JDK WebSocket client (no Origin header), queueing every frame it gets. */
    private static final class TestRenderer implements WebSocket.Listener {

        private final JsonMapper json = JsonMapper.builder().build();
        private final BlockingQueue<JsonNode> frames = new LinkedBlockingQueue<>();
        private final List<String> texts = new CopyOnWriteArrayList<>();
        private final StringBuilder partial = new StringBuilder();
        private final AtomicInteger received = new AtomicInteger();
        private WebSocket socket;

        static TestRenderer connect(URI uri) throws Exception {
            TestRenderer renderer = new TestRenderer();
            renderer.socket = HttpClient.newHttpClient()
                    .newWebSocketBuilder()
                    .buildAsync(uri, renderer)
                    .get(TIMEOUT.toSeconds(), TimeUnit.SECONDS);
            return renderer;
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            partial.append(data);
            if (last) {
                texts.add(partial.toString());
                frames.add(json.readTree(partial.toString()));
                partial.setLength(0);
                received.incrementAndGet();
            }
            webSocket.request(1);
            return null;
        }

        JsonNode next() throws InterruptedException {
            JsonNode frame = poll(TIMEOUT);
            assertThat(frame).as("a frame within %s", TIMEOUT).isNotNull();
            return frame;
        }

        /** The next frame, or {@code null} when none comes within {@code wait}. */
        JsonNode poll(Duration wait) throws InterruptedException {
            return frames.poll(wait.toMillis(), TimeUnit.MILLISECONDS);
        }

        int received() {
            return received.get();
        }

        /** Every frame received so far, as sent. */
        List<String> texts() {
            return List.copyOf(texts);
        }

        void send(String text) {
            socket.sendText(text, true).join();
        }

        void close() {
            socket.sendClose(WebSocket.NORMAL_CLOSURE, "done").join();
        }
    }

    private static Path createTempDirectory() {
        try {
            return Files.createTempDirectory("agents-room-gateway-test");
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
