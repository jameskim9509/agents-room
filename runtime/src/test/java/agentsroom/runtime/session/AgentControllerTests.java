package agentsroom.runtime.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

import agentsroom.contract.AgentErrorEvent;
import agentsroom.contract.AgentEvent;
import agentsroom.contract.AgentEventMessage;
import agentsroom.contract.AgentInfo;
import agentsroom.contract.AgentMessageChunkEvent;
import agentsroom.contract.AgentStartingEvent;
import agentsroom.contract.AgentStatus;
import agentsroom.contract.AgentStatusChangedEvent;
import agentsroom.contract.AuthMethodInfo;
import agentsroom.contract.AuthStatus;
import agentsroom.contract.ErrorCode;
import agentsroom.contract.PermissionOption;
import agentsroom.contract.PermissionOptionKind;
import agentsroom.contract.PermissionOutcome;
import agentsroom.contract.PermissionRequestedEvent;
import agentsroom.contract.PermissionResolvedEvent;
import agentsroom.contract.Readiness;
import agentsroom.contract.RejectReason;
import agentsroom.contract.RunEndedEvent;
import agentsroom.contract.RunOutcome;
import agentsroom.contract.RunStartedEvent;
import agentsroom.contract.ServerMessage;
import agentsroom.contract.SessionEndReason;
import agentsroom.contract.SessionEndedEvent;
import agentsroom.contract.Stage;
import agentsroom.runtime.acp.AgentCallException;
import agentsroom.runtime.acp.AgentConnection;
import agentsroom.runtime.acp.AgentConnectionFactory;
import agentsroom.runtime.acp.AgentConnectionListener;
import agentsroom.runtime.acp.AgentHandshake;
import agentsroom.runtime.acp.AgentUpdate;
import agentsroom.runtime.acp.OpenedSession;
import agentsroom.runtime.acp.PermissionAnswer;
import agentsroom.runtime.acp.PermissionAsk;
import agentsroom.runtime.agent.AgentConfig;
import agentsroom.runtime.harness.ElectronAsNodePreload;
import agentsroom.runtime.harness.HarnessLauncher;
import agentsroom.runtime.permission.PermissionBroker;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Predicate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The ADR 0005 transition table on one agent, with a scripted connection in place of an adapter, for the
 * orderings a real process cannot be made to produce on cue: a stop while a start step blocks, an exit
 * while stopping, a late message of a process already replaced.
 */
class AgentControllerTests {

    private static final Duration WAIT = Duration.ofSeconds(10);

    @TempDir
    Path project;

    private final List<ServerMessage> frames = new ArrayList<>();
    private final BlockingQueue<FakeConnection> connections = new LinkedBlockingQueue<>();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private final PermissionBroker broker = new PermissionBroker();
    private final AgentConfig agent = AgentConfig.seededCodex(UUID.randomUUID().toString());
    private final List<RejectReason> rejections = new ArrayList<>();
    private AgentController controller;

    @AfterEach
    void stopTimers() {
        if (controller != null) {
            controller.shutdown();
        }
        scheduler.shutdownNow();
    }

    @Test
    void aStopWhileAStartStepWaitsEndsOfflineWithoutASessionEnd() throws Exception {
        start();
        FakeConnection connection = connections.poll(WAIT.toMillis(), TimeUnit.MILLISECONDS);
        awaitEvent(AgentStartingEvent.class, event -> event.getData().getStage() == Stage.INITIALIZE);

        controller.stop(this::reject);
        awaitStatus(AgentStatus.OFFLINE);

        assertThat(statuses()).containsExactly(AgentStatus.STARTING, AgentStatus.OFFLINE);
        assertThat(events()).noneMatch(event -> event instanceof AgentErrorEvent || event instanceof SessionEndedEvent);
        assertThat(connection.closed).isTrue();
    }

    @Test
    void theAnswerToTheCancelDecidesTheOutcomeOfAStop() throws Exception {
        FakeConnection connection = startReady();
        controller.sendPrompt("go", this::reject);
        awaitStatus(AgentStatus.RUNNING);

        controller.stop(this::reject);
        awaitStatus(AgentStatus.CANCELLING);
        assertThat(connection.cancelled).isTrue();
        connection.prompt.complete("cancelled");
        awaitStatus(AgentStatus.OFFLINE);

        RunEndedEvent ended = only(RunEndedEvent.class);
        assertThat(ended.getData().getOutcome()).isEqualTo(RunOutcome.CANCELLED);
        assertThat(ended.getData().getStopReason()).isEqualTo("cancelled");
        assertThat(only(SessionEndedEvent.class).getData().getReason()).isEqualTo(SessionEndReason.STOPPED);
        assertThat(statuses()).containsExactly(AgentStatus.STARTING, AgentStatus.READY, AgentStatus.RUNNING,
                AgentStatus.CANCELLING, AgentStatus.OFFLINE);
    }

    @Test
    void aStopWithoutAnAnswerEndsTheRunFailedWithoutAnErrorCode() throws Exception {
        startReady();
        controller.sendPrompt("go", this::reject);
        awaitStatus(AgentStatus.RUNNING);

        controller.stop(this::reject);
        awaitStatus(AgentStatus.OFFLINE);

        RunEndedEvent ended = only(RunEndedEvent.class);
        assertThat(ended.getData().getOutcome()).isEqualTo(RunOutcome.FAILED);
        assertThat(ended.getData().getErrorCode()).isNull();
        assertThat(ended.getData().getStopReason()).isNull();
    }

    @Test
    void anExitWhileStoppingIsTheStopsEndWhateverItsCode() throws Exception {
        FakeConnection connection = startReady();
        controller.sendPrompt("go", this::reject);
        awaitStatus(AgentStatus.RUNNING);

        controller.stop(this::reject);
        awaitStatus(AgentStatus.CANCELLING);
        connection.listener.onExit(1, "exited with code 1", 0);
        awaitStatus(AgentStatus.OFFLINE);

        assertThat(only(SessionEndedEvent.class).getData().getReason()).isEqualTo(SessionEndReason.STOPPED);
        assertThat(events()).noneMatch(AgentErrorEvent.class::isInstance);
    }

    @Test
    void anExitBeforeInitializeAnsweredIsAStartFailure() throws Exception {
        start();
        FakeConnection connection = connections.poll(WAIT.toMillis(), TimeUnit.MILLISECONDS);

        connection.listener.onExit(2, "exited with code 2", 0);
        awaitStatus(AgentStatus.ERROR);

        AgentErrorEvent error = only(AgentErrorEvent.class);
        assertThat(error.getData().getCode()).isEqualTo(ErrorCode.ADAPTER_START_FAILED);
        assertThat(error.getData().getStage()).isEqualTo(Stage.SPAWN);
        assertThat(connection.killed).as("error means the process is gone").isTrue();
    }

    @Test
    void anExitAfterInitializeIsProcessExitedAtTheStageItHappenedIn() throws Exception {
        start();
        FakeConnection connection = connections.poll(WAIT.toMillis(), TimeUnit.MILLISECONDS);
        connection.initialize.complete(handshake(null));
        awaitEvent(AgentStartingEvent.class, event -> event.getData().getStage() == Stage.SESSION_NEW);

        connection.listener.onExit(5, "exited with code 5", 0);
        awaitStatus(AgentStatus.ERROR);

        AgentErrorEvent error = only(AgentErrorEvent.class);
        assertThat(error.getData().getCode()).isEqualTo(ErrorCode.PROCESS_EXITED);
        assertThat(error.getData().getStage()).isEqualTo(Stage.SESSION_NEW);
        assertThat(events()).noneMatch(SessionEndedEvent.class::isInstance);
    }

    @Test
    void aLateMessageOfAReplacedProcessIsDropped() throws Exception {
        FakeConnection first = startReady();
        first.listener.onExit(1, "exited with code 1", 0);
        awaitStatus(AgentStatus.ERROR);
        FakeConnection second = startReady();
        int before = events().size();

        first.listener.onUpdate(first.sessionId, chunk("from the old process"), 0);
        second.listener.onUpdate(second.sessionId, chunk("from the new process"), 0);
        awaitEvent(AgentMessageChunkEvent.class, event -> true);

        List<AgentEvent> after = events().subList(before, events().size());
        assertThat(after).singleElement().satisfies(event -> assertThat(
                ((AgentMessageChunkEvent) event).getData().getText())
                .isEqualTo("from the new process"));
    }

    /**
     * ADR 0005 decision 11: the start of a key at the end of a chunk is held back; the next event of the
     * agent takes it first, in its own chunk event, so the text keeps its place in seq order.
     */
    @Test
    void aHeldStartOfAKeyGoesOutBeforeTheNextEventInSeqOrder() throws Exception {
        FakeConnection connection = startReady();
        controller.sendPrompt("go", this::reject);
        awaitStatus(AgentStatus.RUNNING);
        int before = events().size();

        connection.listener.onUpdate(connection.sessionId, chunk("the key sk-moc"), 1);
        connection.prompt.complete("end_turn");
        awaitEvent(RunEndedEvent.class, event -> true);

        List<AgentEvent> after = events().subList(before, events().size());
        assertThat(after).extracting(event -> event.getClass().getSimpleName()).startsWith("AgentMessageChunkEvent",
                "AgentMessageChunkEvent", "RunEndedEvent");
        assertThat(after.subList(0, 2)).extracting(event ->
                ((AgentMessageChunkEvent) event).getData().getText())
                .containsExactly("the key ", "sk-moc");
        assertThat(after.get(1).getRunId()).as("still the run's text").isEqualTo(after.get(2).getRunId());
        assertThat(seqs()).isSorted().doesNotHaveDuplicates();
    }

    /**
     * Text an adapter sends between runs is no run's: a start it held back goes out before the next run
     * starts, without that run's id, and the run's own text starts a new stream.
     */
    @Test
    void textHeldBackBetweenRunsGoesOutBeforeTheNextRunAndOutsideIt() throws Exception {
        FakeConnection connection = startReady();
        int before = events().size();

        connection.listener.onUpdate(connection.sessionId, chunk("between runs sk-moc"), 1);
        awaitEvent(AgentMessageChunkEvent.class, event -> event.getData().getText().equals("between runs "));
        controller.sendPrompt("go", this::reject);
        RunStartedEvent started = awaitEvent(RunStartedEvent.class, event -> true);
        connection.listener.onUpdate(connection.sessionId, chunk("kmockmock"), 2);
        awaitEvent(AgentMessageChunkEvent.class, event -> event.getData().getText().equals("kmockmock"));

        List<AgentEvent> after = events().subList(before, events().size());
        assertThat(after).extracting(event -> event.getClass().getSimpleName()).startsWith("AgentMessageChunkEvent",
                "AgentMessageChunkEvent", "RunStartedEvent");
        assertThat(((AgentMessageChunkEvent) after.get(1)).getData().getText()).isEqualTo("sk-moc");
        assertThat(after.get(1).getRunId()).as("held before the run").isNull();
        assertThat(after.getLast().getRunId()).isEqualTo(started.getRunId());
        assertThat(seqs()).isSorted().doesNotHaveDuplicates();
    }

    @Test
    void aPermissionRequestOfACancellingRunIsAnsweredCancelledAtOnce() throws Exception {
        FakeConnection connection = startReady();
        controller.sendPrompt("go", this::reject);
        awaitStatus(AgentStatus.RUNNING);
        controller.stop(this::reject);
        awaitStatus(AgentStatus.CANCELLING);

        CompletableFuture<PermissionAnswer> answer = new CompletableFuture<>();
        connection.listener.onPermissionRequest(ask(), 0, answer);

        assertThat(answer.get(WAIT.toMillis(), TimeUnit.MILLISECONDS)).isEqualTo(new PermissionAnswer.Cancelled());
        PermissionResolvedEvent resolved = awaitEvent(PermissionResolvedEvent.class, event -> true);
        assertThat(resolved.getData().getOutcome()).isEqualTo(PermissionOutcome.CANCELLED);
        assertThat(only(PermissionRequestedEvent.class).getData().getPermissionId())
                .isEqualTo(resolved.getData().getPermissionId());
        connection.prompt.complete("cancelled");
        awaitStatus(AgentStatus.OFFLINE);
    }

    @Test
    void onlyAnOfferedOptionAnswersAndTheRunGoesOn() throws Exception {
        FakeConnection connection = startReady();
        controller.sendPrompt("go", this::reject);
        awaitStatus(AgentStatus.RUNNING);
        CompletableFuture<PermissionAnswer> answer = new CompletableFuture<>();
        connection.listener.onPermissionRequest(ask(), 0, answer);
        String permissionId = awaitEvent(PermissionRequestedEvent.class, event -> true).getData().getPermissionId();
        awaitStatus(AgentStatus.AWAITING_PERMISSION);

        controller.respondPermission(permissionId, "not-offered", this::reject);
        controller.respondPermission(permissionId, "yes", this::reject);
        awaitCondition(() -> statuses().equals(List.of(AgentStatus.STARTING, AgentStatus.READY, AgentStatus.RUNNING,
                AgentStatus.AWAITING_PERMISSION, AgentStatus.RUNNING)));

        assertThat(answer.get(WAIT.toMillis(), TimeUnit.MILLISECONDS)).isEqualTo(new PermissionAnswer.Selected("yes"));
        synchronized (frames) {
            assertThat(rejections).containsExactly(RejectReason.INVALID_MESSAGE);
        }
        assertThat(broker.find(permissionId)).isEmpty();
    }

    @Test
    void refusesCommandsTheStatusDoesNotAllow() throws Exception {
        controller = newController();
        controller.sendPrompt("too early", this::reject);
        controller.stop(this::reject);
        awaitRejections(2);

        startReady();
        controller.start(this::reject);
        controller.checkConnection(this::reject);
        awaitRejections(4);

        synchronized (frames) {
            assertThat(rejections).containsOnly(RejectReason.INVALID_STATE);
        }
    }

    @Test
    void notLoggedInMakesTheAgentAuthRequiredUntilALoginIsReported() throws Exception {
        start();
        FakeConnection connection = connections.poll(WAIT.toMillis(), TimeUnit.MILLISECONDS);
        connection.listener.onAuthStatus(status("none", "Not logged in"), 0);
        connection.initialize.complete(handshake(true));
        awaitEvent(AgentStartingEvent.class, event -> event.getData().getStage() == Stage.AUTHENTICATE);
        synchronized (frames) {
            assertThat(controller.readiness()).isEqualTo(Readiness.AUTH_REQUIRED);
        }

        connection.listener.onAuthStatus(status("account", "ChatGPT Plus"), 0);
        connection.authenticate.complete(null);
        connection.newSession.complete(new OpenedSession("s1", null, null));
        awaitStatus(AgentStatus.READY);

        synchronized (frames) {
            assertThat(controller.readiness()).isEqualTo(Readiness.CONFIGURED);
        }
        assertThat(seqs()).as("seq counts from 1 without gaps").isEqualTo(
                java.util.stream.LongStream.rangeClosed(1, seqs().size()).boxed().toList());
    }

    /** D8: a reported login (here an API key) is never replaced, even when session/new asks for one. */
    @Test
    void aReportedLoginIsNotReplacedWhenSessionNewAsksForAuthentication() throws Exception {
        start();
        FakeConnection connection = connections.poll(WAIT.toMillis(), TimeUnit.MILLISECONDS);
        connection.listener.onAuthStatus(status("api_key", "OpenAI API key"), 0);
        connection.initialize.complete(handshake(true));
        awaitEvent(AgentStartingEvent.class, event -> event.getData().getStage() == Stage.SESSION_NEW);

        connection.newSession.completeExceptionally(authenticationRequired());
        AgentErrorEvent error = awaitEvent(AgentErrorEvent.class, event -> true);

        assertThat(error.getData().getCode()).isEqualTo(ErrorCode.AUTH_REQUIRED);
        assertThat(error.getData().getStage()).isEqualTo(Stage.SESSION_NEW);
        assertThat(connection.authenticateCalled).as("no authenticate over a reported login").isFalse();
        assertThat(stages()).containsExactly(Stage.SPAWN, Stage.INITIALIZE, Stage.SESSION_NEW);
    }

    /** D8: a status that comes after the wait, before session/new answers, still decides. */
    @Test
    void aStatusPushedAfterTheWaitStillKeepsTheLogin() throws Exception {
        start();
        FakeConnection connection = connections.poll(WAIT.toMillis(), TimeUnit.MILLISECONDS);
        connection.initialize.complete(handshake(true));
        // The wait (300 ms here) ends without a status.
        awaitEvent(AgentStartingEvent.class, event -> event.getData().getStage() == Stage.SESSION_NEW);

        connection.listener.onAuthStatus(status("account", "ChatGPT Plus"), 0);
        connection.newSession.completeExceptionally(authenticationRequired());
        AgentErrorEvent error = awaitEvent(AgentErrorEvent.class, event -> true);

        assertThat(error.getData().getCode()).isEqualTo(ErrorCode.AUTH_REQUIRED);
        assertThat(connection.authenticateCalled).isFalse();
    }

    /** D8: no status at all, and session/new asks: sign in, then session/new once more. */
    @Test
    void withoutAStatusSessionNewAskingForAuthenticationSignsInAndRetries() throws Exception {
        start();
        FakeConnection connection = connections.poll(WAIT.toMillis(), TimeUnit.MILLISECONDS);
        connection.initialize.complete(handshake(true));
        awaitEvent(AgentStartingEvent.class, event -> event.getData().getStage() == Stage.SESSION_NEW);

        connection.newSession.completeExceptionally(authenticationRequired());
        awaitEvent(AgentStartingEvent.class, event -> event.getData().getStage() == Stage.AUTHENTICATE);
        assertThat(connection.authenticateCalled).isTrue();
        connection.authenticate.complete(null);
        awaitCondition(() -> stages().size() == 5);
        assertThat(stages()).containsExactly(Stage.SPAWN, Stage.INITIALIZE, Stage.SESSION_NEW, Stage.AUTHENTICATE,
                Stage.SESSION_NEW);
    }

    /** ADR 0005 decision 7: a request the adapter withdraws is closed cancelled; nobody can answer it. */
    @Test
    void aWithdrawnPermissionRequestIsClosedCancelledAndTheRunGoesOn() throws Exception {
        FakeConnection connection = startReady();
        controller.sendPrompt("go", this::reject);
        awaitStatus(AgentStatus.RUNNING);
        CompletableFuture<PermissionAnswer> answer = new CompletableFuture<>();
        connection.listener.onPermissionRequest(ask(), 0, answer);
        String permissionId = awaitEvent(PermissionRequestedEvent.class, event -> true).getData().getPermissionId();
        awaitStatus(AgentStatus.AWAITING_PERMISSION);

        // What the SDK does on $/cancel_request.
        answer.cancel(false);
        PermissionResolvedEvent resolved = awaitEvent(PermissionResolvedEvent.class, event -> true);
        awaitCondition(() -> statuses().getLast() == AgentStatus.RUNNING);

        assertThat(resolved.getData().getPermissionId()).isEqualTo(permissionId);
        assertThat(resolved.getData().getOutcome()).isEqualTo(PermissionOutcome.CANCELLED);
        assertThat(resolved.getData().getOptionId()).isNull();
        assertThat(broker.find(permissionId)).isEmpty();
        controller.respondPermission(permissionId, "yes", this::reject);
        awaitRejections(1);
        synchronized (frames) {
            assertThat(rejections).containsExactly(RejectReason.UNKNOWN_PERMISSION);
        }
        assertThat(events()).filteredOn(PermissionResolvedEvent.class::isInstance).hasSize(1);
    }

    /** Runtime shutdown ends every process of the agent, also a connection check's. */
    @Test
    void shutdownKillsTheProcessOfAConnectionCheck() throws Exception {
        controller = newController();
        controller.checkConnection(this::reject);
        FakeConnection connection = connections.poll(WAIT.toMillis(), TimeUnit.MILLISECONDS);
        assertThat(connection).as("the check opened a connection").isNotNull();

        controller.shutdown();

        assertThat(connection.killed).isTrue();
    }

    // ── Helpers ───────────────────────────────────────────────────────

    private static AgentCallException authenticationRequired() {
        return new AgentCallException(AgentCallException.Kind.ERROR_RESPONSE, AgentCallException.AUTHENTICATION_REQUIRED,
                "JSON-RPC error -32000: Authentication required", null);
    }

    private List<Stage> stages() {
        return events().stream().filter(AgentStartingEvent.class::isInstance)
                .map(event -> ((AgentStartingEvent) event).getData().getStage()).toList();
    }

    private AgentController newController() {
        HarnessLauncher launcher = new HarnessLauncher(
                Map.of(HarnessLauncher.CODEX_COMMAND, "[\"fake-adapter\"]")::get, project,
                new ElectronAsNodePreload(project.resolve("preload")));
        AgentTimeouts timeouts = new AgentTimeouts(Duration.ofSeconds(5), Duration.ofMillis(300), Duration.ofSeconds(5),
                Duration.ofSeconds(5), Duration.ofMillis(300), Duration.ofSeconds(1));
        StatePublisher publisher = change -> {
            synchronized (frames) {
                frames.addAll(change.get());
                frames.notifyAll();
            }
        };
        AgentConnectionFactory factory = (spec, listener) -> {
            FakeConnection connection = new FakeConnection(listener);
            connections.add(connection);
            return connection;
        };
        return new AgentController(agent, project, new AgentServices(publisher, launcher, factory, broker, timeouts,
                Clock.systemUTC(), scheduler, ignored -> {
                }));
    }

    private void start() {
        if (controller == null) {
            controller = newController();
        }
        controller.start(this::reject);
    }

    /** Starts and opens the session: initialize answered, no auth status, session/new answered. */
    private FakeConnection startReady() throws Exception {
        int readyBefore = (int) statuses().stream().filter(status -> status == AgentStatus.READY).count();
        start();
        FakeConnection connection = connections.poll(WAIT.toMillis(), TimeUnit.MILLISECONDS);
        assertThat(connection).as("a connection was opened").isNotNull();
        connection.initialize.complete(handshake(null));
        connection.newSession.complete(new OpenedSession(connection.sessionId, null, null));
        awaitCondition(() -> statuses().stream().filter(status -> status == AgentStatus.READY).count() > readyBefore);
        return connection;
    }

    private void reject(RejectReason reason, String message, String agentId) {
        synchronized (frames) {
            rejections.add(reason);
            frames.notifyAll();
        }
    }

    private void awaitRejections(int count) throws InterruptedException {
        awaitCondition(() -> {
            synchronized (frames) {
                return rejections.size() >= count;
            }
        });
    }

    private void awaitStatus(AgentStatus status) throws InterruptedException {
        awaitEvent(AgentStatusChangedEvent.class, event -> event.getData().getStatus() == status);
    }

    private <T extends AgentEvent> T awaitEvent(Class<T> type, Predicate<T> matches) throws InterruptedException {
        long deadline = System.nanoTime() + WAIT.toNanos();
        synchronized (frames) {
            while (true) {
                for (AgentEvent event : events()) {
                    if (type.isInstance(event) && matches.test(type.cast(event))) {
                        return type.cast(event);
                    }
                }
                long left = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
                if (left <= 0) {
                    fail("No matching " + type.getSimpleName() + " in " + events());
                }
                frames.wait(left);
            }
        }
    }

    private void awaitCondition(java.util.function.BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + WAIT.toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                fail("condition not met; events " + events());
            }
            Thread.sleep(10);
        }
    }

    private List<AgentEvent> events() {
        synchronized (frames) {
            return frames.stream().filter(AgentEventMessage.class::isInstance)
                    .map(frame -> ((AgentEventMessage) frame).getEvent()).toList();
        }
    }

    private List<AgentStatus> statuses() {
        return events().stream().filter(AgentStatusChangedEvent.class::isInstance)
                .map(event -> ((AgentStatusChangedEvent) event).getData().getStatus()).toList();
    }

    private List<Long> seqs() {
        return events().stream().map(AgentEvent::getSeq).toList();
    }

    private <T extends AgentEvent> T only(Class<T> type) {
        List<T> matching = events().stream().filter(type::isInstance).map(type::cast).toList();
        assertThat(matching).as("%s events", type.getSimpleName()).hasSize(1);
        return matching.getFirst();
    }

    private static AgentHandshake handshake(Boolean pushesAuthStatus) {
        AgentInfo info = new AgentInfo();
        info.setName("fake");
        info.setVersion("1");
        AuthMethodInfo chatGpt = new AuthMethodInfo();
        chatGpt.setId("chat-gpt");
        chatGpt.setName("ChatGPT");
        return new AgentHandshake(1, info, List.of(chatGpt), pushesAuthStatus != null && pushesAuthStatus);
    }

    private static AuthStatus status(String kind, String label) {
        AuthStatus status = new AuthStatus();
        status.setKind(kind);
        status.setLabel(label);
        return status;
    }

    private static AgentUpdate chunk(String text) {
        return new AgentUpdate.Chunk(AgentUpdate.ChunkKind.AGENT, null, "text", text);
    }

    private static PermissionAsk ask() {
        PermissionOption yes = new PermissionOption();
        yes.setOptionId("yes");
        yes.setName("Yes");
        yes.setKind(PermissionOptionKind.ALLOW_ONCE);
        return new PermissionAsk("s", new AgentUpdate.ToolCallReport(false, "c1", "Run it", null, null, null, null,
                null, null, null), List.of(yes));
    }

    /** A connection whose answers the test gives; closing or killing it fails what still waits. */
    private static final class FakeConnection implements AgentConnection {

        final AgentConnectionListener listener;
        final String sessionId = "session-" + UUID.randomUUID();
        final CompletableFuture<AgentHandshake> initialize = new CompletableFuture<>();
        final CompletableFuture<Void> authenticate = new CompletableFuture<>();
        final CompletableFuture<OpenedSession> newSession = new CompletableFuture<>();
        final CompletableFuture<String> prompt = new CompletableFuture<>();
        volatile boolean authenticateCalled;
        volatile boolean cancelled;
        volatile boolean closed;
        volatile boolean killed;

        FakeConnection(AgentConnectionListener listener) {
            this.listener = listener;
        }

        @Override
        public Optional<ProcessHandle> process() {
            return Optional.empty();
        }

        @Override
        public AgentHandshake initialize(Duration timeout) throws AgentCallException {
            return await(initialize, timeout);
        }

        @Override
        public void authenticate(String methodId, Duration timeout) throws AgentCallException {
            authenticateCalled = true;
            await(authenticate, timeout);
        }

        @Override
        public OpenedSession newSession(Path cwd, Duration timeout) throws AgentCallException {
            return await(newSession, timeout);
        }

        @Override
        public CompletableFuture<String> prompt(String sessionId, String text) {
            return prompt;
        }

        @Override
        public void cancel(String sessionId) {
            cancelled = true;
        }

        @Override
        public void close(Duration timeout) {
            closed = true;
            end();
        }

        @Override
        public void kill() {
            killed = true;
            end();
        }

        private void end() {
            AgentCallException gone = new AgentCallException(AgentCallException.Kind.CLOSED, "closed");
            for (CompletableFuture<?> pending : List.of(initialize, authenticate, newSession, prompt)) {
                pending.completeExceptionally(gone);
            }
        }

        private static <T> T await(CompletableFuture<T> future, Duration timeout) throws AgentCallException {
            try {
                return future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            } catch (TimeoutException e) {
                throw new AgentCallException(AgentCallException.Kind.TIMEOUT, "timeout");
            } catch (ExecutionException e) {
                throw e.getCause() instanceof AgentCallException call ? call
                        : new AgentCallException(AgentCallException.Kind.FAILED, e.getMessage());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AgentCallException(AgentCallException.Kind.CLOSED, "interrupted");
            }
        }
    }
}
