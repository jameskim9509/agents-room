package agentsroom.runtime.session;

import agentsroom.contract.AgentErrorData;
import agentsroom.contract.AgentErrorEvent;
import agentsroom.contract.AgentEvent;
import agentsroom.contract.AgentEventMessage;
import agentsroom.contract.AgentSnapshot;
import agentsroom.contract.AgentStatus;
import agentsroom.contract.AgentSummary;
import agentsroom.contract.AuthMethodInfo;
import agentsroom.contract.AuthStatus;
import agentsroom.contract.AvailableCommand;
import agentsroom.contract.ConnectionChecked;
import agentsroom.contract.DerivedToolStatus;
import agentsroom.contract.ErrorCode;
import agentsroom.contract.PermissionOutcome;
import agentsroom.contract.PermissionRequestedData;
import agentsroom.contract.PlanEntry;
import agentsroom.contract.Readiness;
import agentsroom.contract.RejectReason;
import agentsroom.contract.RunEndedData;
import agentsroom.contract.RunEndedEvent;
import agentsroom.contract.RunOutcome;
import agentsroom.contract.RunStartedEvent;
import agentsroom.contract.ServerMessage;
import agentsroom.contract.SessionEndReason;
import agentsroom.contract.SessionEndedData;
import agentsroom.contract.SessionEndedEvent;
import agentsroom.contract.SessionInfoData;
import agentsroom.contract.SessionSnapshot;
import agentsroom.contract.SessionStartedData;
import agentsroom.contract.SessionStartedEvent;
import agentsroom.contract.Stage;
import agentsroom.contract.ToolCallState;
import agentsroom.contract.UnfinishedToolCall;
import agentsroom.contract.UsageData;
import agentsroom.runtime.acp.AdapterValues;
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
import agentsroom.runtime.agent.HarnessCatalog;
import agentsroom.runtime.harness.AdapterEnvironment;
import agentsroom.runtime.harness.HarnessLaunch;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * One agent at run time: its adapter process and ACP session, its status, its event stream and the
 * state a snapshot shows (ADR 0005). The status follows the ADR 0005 transition table exactly; the
 * runtime is its single source.
 *
 * <p><b>Threads.</b> Everything that reads or changes this agent's state runs on its own mailbox, one
 * task at a time: commands, the adapter's messages (the connection's inbound thread only enqueues
 * them), and the results of the blocking steps, which run on their own threads (start, close, check).
 * Each change and the frames describing it go out through {@link StatePublisher#publish} as one step,
 * so snapshots never see half of it; {@code seq} is assigned there, in emission order.
 *
 * <p><b>Generations.</b> Every start is a new generation. A message, answer or step result of an older
 * generation (a process being stopped or already failed) is dropped, so a late event never reaches a
 * new process's stream.
 */
final class AgentController {

    /** codex-acp's auth kind for "not logged in" (critique G5). */
    static final String NOT_LOGGED_IN = "none";

    /** Hidden from the choices the runtime reports: MVP never asks for an API key (SCRUM-65, ADR 0004). */
    static final String HIDDEN_AUTH_METHOD = "api-key";

    /** ADR 0003 decision 1. */
    static final int PROTOCOL_VERSION = 1;

    /** Updates that arrive before the session/new answer is handled wait at most this many. */
    private static final int EARLY_UPDATE_LIMIT = 256;

    /** The content type of a text chunk. */
    private static final String TEXT = "text";

    /**
     * A call that failed because the connection is gone waits this long for the exit report, which
     * carries the exit code, before it ends the agent itself.
     */
    private static final Duration CONNECTION_LOST_GRACE = Duration.ofSeconds(6);

    private static final Logger log = LoggerFactory.getLogger(AgentController.class);

    private final AgentConfig agent;
    private final Path projectPath;
    private final AgentServices services;
    private final String label;
    private final ExecutorService mailbox;

    // ── State: written on the mailbox inside publish; read under the publish lock ──

    private AgentStatus status = AgentStatus.OFFLINE;
    private long lastSeq;
    private final RecentEvents recent = new RecentEvents();
    private final ChunkSeams seams = new ChunkSeams();
    private final TerminalSeams terminals = new TerminalSeams();

    private int generation;
    /** Whether the current generation's process is up (not failed, not past the stop's close). */
    private volatile boolean live;
    private volatile boolean stopping;
    private volatile boolean checking;
    private volatile AgentConnection connection;
    private Stage stage;
    private boolean initialized;
    private AgentHandshake handshake;

    private String sessionId;
    private SessionView session;
    private final List<EarlyUpdate> earlyUpdates = new ArrayList<>();

    private Run run;
    /** The current run's prompt, or the last ended run's while {@link #lastRun} is set (masked). */
    private String prompt;
    private RunEndedData lastRun;
    /** The last ended run's tool calls, so that an update arriving after the run still merges. */
    private Map<String, ToolCallState> lastRunToolCalls = Map.of();
    private SessionEndedData sessionEnd;
    private AgentErrorData lastError;
    private AuthStatus authStatus;
    private final Map<String, PermissionRequestedData> pendingPermissions = new LinkedHashMap<>();

    // ── What the last start or check observed, for the readiness (D9) ──

    /** The adapter last reported "not logged in". */
    private volatile boolean authRequired;
    /** The adapter last answered {@code initialize} without the configured sign-in method. */
    private volatile boolean signInMissing;
    /** The adapter's executable was missing at the last start or check. */
    private volatile boolean spawnMissing;

    /**
     * Every adapter process this agent has open: the session's, a check's, one that is starting or
     * closing. Runtime shutdown kills them all, whatever step they are in.
     */
    private final Set<AgentConnection> openConnections = ConcurrentHashMap.newKeySet();
    private volatile boolean shutDown;

    AgentController(AgentConfig agent, Path projectPath, AgentServices services) {
        this.agent = agent;
        this.projectPath = projectPath;
        this.services = services;
        this.label = agent.name() + " " + agent.agentId().substring(0, Math.min(8, agent.agentId().length()));
        this.mailbox = Executors.newSingleThreadExecutor(Thread.ofVirtual().name("agent-" + label).factory());
    }

    AgentConfig agent() {
        return agent;
    }

    // ── Read side (under the publish lock) ─────────────────────────────

    /** Whether anything runs: a process, a stop or a connection check. Blocks opening another project. */
    boolean isActive() {
        return checking || stopping || (status != AgentStatus.OFFLINE && status != AgentStatus.ERROR);
    }

    /**
     * The readiness this agent's card shows (architecture.md, Agent config registry; D9): the static
     * checks first, then what the last start or connection check observed. {@code connectionChecked}
     * reports this same value, so the card and the check never disagree.
     */
    Readiness readiness() {
        if (!HarnessCatalog.supports(agent)) {
            return Readiness.UNSUPPORTED;
        }
        if (!services.launcher().isAvailable(agent.harnessId()) || spawnMissing) {
            return Readiness.EXECUTABLE_MISSING;
        }
        if (signInMissing) {
            return Readiness.UNSUPPORTED;
        }
        return authRequired ? Readiness.AUTH_REQUIRED : Readiness.CONFIGURED;
    }

    /** The agent for a snapshot (ADR 0005 decision 2). */
    AgentSnapshot snapshot(AgentSummary summary) {
        AgentSnapshot snapshot = new AgentSnapshot();
        snapshot.setAgent(summary);
        snapshot.setStatus(status);
        snapshot.setSessionId(sessionId);
        snapshot.setRunId(run == null ? null : run.runId);
        snapshot.setLastSeq(lastSeq);
        snapshot.setToolCalls(run == null ? List.of() : List.copyOf(run.toolCalls.values()));
        snapshot.setPendingPermissions(List.copyOf(pendingPermissions.values()));
        snapshot.setRecentEvents(recent.list());
        if (run != null || lastRun != null) {
            snapshot.setPrompt(prompt);
        }
        snapshot.setLastRun(lastRun);
        if (session != null) {
            snapshot.setSession(session.snapshot());
        }
        snapshot.setSessionEnd(sessionEnd);
        if (status == AgentStatus.ERROR) {
            snapshot.setLastError(lastError);
        }
        snapshot.setAuthStatus(authStatus);
        return snapshot;
    }

    // ── Commands (from the gateway; each runs on the mailbox) ─────────

    void start(CommandReply reply) {
        post(() -> handleStart(reply));
    }

    void stop(CommandReply reply) {
        post(() -> handleStop(reply));
    }

    void sendPrompt(String text, CommandReply reply) {
        post(() -> handlePrompt(text, reply));
    }

    void respondPermission(String permissionId, String optionId, CommandReply reply) {
        post(() -> handleRespond(permissionId, optionId, reply));
    }

    void checkConnection(CommandReply reply) {
        post(() -> handleCheck(reply));
    }

    /**
     * Runtime shutdown: kills every adapter process tree of this agent at once, without events (the
     * renderers are gone): the session's, a connection check's, and one still starting or closing.
     */
    void shutdown() {
        shutDown = true;
        mailbox.shutdownNow();
        for (AgentConnection open : List.copyOf(openConnections)) {
            open.kill();
            openConnections.remove(open);
        }
    }

    /** Ends the mailbox of an agent that is no longer shown (its project was closed). */
    void retire() {
        mailbox.shutdown();
    }

    // ── startAgent ────────────────────────────────────────────────────

    private void handleStart(CommandReply reply) {
        if (checking) {
            reply.reject(RejectReason.INVALID_STATE, "The connection check of this agent is still running");
            return;
        }
        if (status != AgentStatus.OFFLINE && status != AgentStatus.ERROR) {
            reply.reject(RejectReason.INVALID_STATE, "The agent is not offline or in error");
            return;
        }
        int gen = ++generation;
        live = true;
        stopping = false;
        initialized = false;
        handshake = null;
        connection = null;
        long now = now();
        if (!HarnessCatalog.supports(agent)) {
            publish(frames -> {
                setStatus(frames, AgentStatus.STARTING, now);
                live = false;
                failFrames(frames, ErrorCode.MODEL_INCOMPATIBLE, Stage.CONFIG,
                        "The harness does not run with this model provider and auth method", null, null, now);
            });
            return;
        }
        publish(frames -> {
            lastError = null;
            setStatus(frames, AgentStatus.STARTING, now);
            enterStage(frames, Stage.SPAWN, now);
        });
        log.info("[{}] Starting ({})", label, agent.harnessId());
        Thread.ofVirtual().name("agent-start-" + label).start(() -> runStart(gen));
    }

    /** The start steps (ADR 0005 transition table, D8), off the mailbox: they block. */
    private void runStart(int gen) {
        HarnessLaunch launch = services.launcher().resolve(agent.harnessId());
        if (launch instanceof HarnessLaunch.Unavailable unavailable) {
            post(() -> fail(gen, ErrorCode.EXECUTABLE_MISSING, Stage.SPAWN, unavailable.reason(), null, null));
            return;
        }
        HarnessLaunch.Ready ready = (HarnessLaunch.Ready) launch;
        AuthStatusReports reports = new AuthStatusReports();
        AgentConnection opened;
        try {
            opened = open(launchSpec(ready), listener(gen, reports));
        } catch (AgentCallException e) {
            boolean missing = e.kind() == AgentCallException.Kind.EXECUTABLE_MISSING;
            post(() -> {
                observeSpawn(gen, missing);
                fail(gen, missing ? ErrorCode.EXECUTABLE_MISSING : ErrorCode.ADAPTER_START_FAILED, Stage.SPAWN,
                        e.getMessage(), null, null);
            });
            return;
        }
        if (opened == null) {
            return;
        }
        if (!post(() -> attach(gen, opened))) {
            dispose(opened);
            return;
        }

        post(() -> enterStage(gen, Stage.INITIALIZE));
        AgentHandshake answered;
        try {
            answered = opened.initialize(services.timeouts().initialize());
        } catch (AgentCallException e) {
            switch (e.kind()) {
                case CLOSED -> connectionLost(gen);
                case TIMEOUT -> post(() -> fail(gen, ErrorCode.ACP_INIT_FAILED, Stage.INITIALIZE,
                        "initialize got no answer within " + services.timeouts().initialize().toSeconds() + " s",
                        null, null));
                default -> post(() -> fail(gen, ErrorCode.ACP_INIT_FAILED, Stage.INITIALIZE, e.getMessage(), null, null));
            }
            return;
        }
        if (answered.protocolVersion() != PROTOCOL_VERSION) {
            post(() -> fail(gen, ErrorCode.PROTOCOL_UNSUPPORTED, Stage.INITIALIZE, "The adapter answered protocol version "
                    + answered.protocolVersion() + "; this runtime speaks " + PROTOCOL_VERSION, null, null));
            return;
        }
        post(() -> initialized(gen, answered));
        if (!answered.offers(agent.authMethodId())) {
            post(() -> fail(gen, ErrorCode.AUTH_FAILED, Stage.AUTHENTICATE,
                    "The adapter does not offer the '" + agent.authMethodId() + "' sign-in", null, null));
            return;
        }

        // D8: a reported "not logged in" signs in before session/new. Any other status (a ChatGPT login,
        // an API key) is never replaced: authenticate(chat-gpt) would start a new login over it. Only an
        // adapter that advertises the status push is waited for.
        boolean authenticated = false;
        AuthStatus reported = null;
        if (answered.pushesAuthStatus()) {
            Duration wait = services.timeouts().authStatusWait();
            reported = await(reports.first, wait);
            if (reported == null) {
                log.info("[{}] No auth status within {} ms; session/new decides whether to sign in (D8)", label,
                        wait.toMillis());
            }
        }
        if (reported != null && NOT_LOGGED_IN.equals(reported.getKind())) {
            if (!authenticate(gen, opened)) {
                return;
            }
            authenticated = true;
        }
        while (true) {
            post(() -> enterStage(gen, Stage.SESSION_NEW));
            try {
                OpenedSession session = opened.newSession(projectPath, services.timeouts().sessionNew());
                post(() -> sessionOpened(gen, session));
                return;
            } catch (AgentCallException e) {
                if (e.isAuthenticationRequired() && !authenticated) {
                    // The latest status, which may have come after the wait: a late push counts too.
                    AuthStatus latest = reports.latest.get();
                    if (latest == null || NOT_LOGGED_IN.equals(latest.getKind())) {
                        // No status, or "not logged in", and the adapter wants a login: sign in, retry once.
                        if (!authenticate(gen, opened)) {
                            return;
                        }
                        authenticated = true;
                        continue;
                    }
                    log.warn("[{}] session/new asks for a sign-in although the adapter reported a '{}' login; not "
                            + "signing in over it (D8)", label, AdapterValues.text(latest.getKind()));
                }
                switch (e.kind()) {
                    case CLOSED -> connectionLost(gen);
                    case ERROR_RESPONSE -> post(() -> fail(gen,
                            e.isAuthenticationRequired() ? ErrorCode.AUTH_REQUIRED : ErrorCode.SESSION_CREATE_FAILED,
                            Stage.SESSION_NEW, e.getMessage(), null, null));
                    case TIMEOUT -> post(() -> fail(gen, ErrorCode.SESSION_CREATE_FAILED, Stage.SESSION_NEW,
                            "session/new got no answer within " + services.timeouts().sessionNew().toSeconds() + " s",
                            null, null));
                    default -> post(() -> fail(gen, ErrorCode.SESSION_CREATE_FAILED, Stage.SESSION_NEW, e.getMessage(),
                            null, null));
                }
                return;
            }
        }
    }

    /**
     * The {@code authenticate} stage: the adapter's own sign-in (codex-acp opens the browser), bounded by
     * the runtime's timeout because the adapter waits forever (D8).
     *
     * @return whether to go on
     */
    private boolean authenticate(int gen, AgentConnection opened) {
        post(() -> enterStage(gen, Stage.AUTHENTICATE));
        try {
            opened.authenticate(agent.authMethodId(), services.timeouts().authenticate());
            return true;
        } catch (AgentCallException e) {
            switch (e.kind()) {
                case CLOSED -> connectionLost(gen);
                case TIMEOUT -> post(() -> fail(gen, ErrorCode.AUTH_FAILED, Stage.AUTHENTICATE,
                        "The sign-in did not complete within " + services.timeouts().authenticate().toSeconds() + " s",
                        null, null));
                default -> post(() -> fail(gen, ErrorCode.AUTH_FAILED, Stage.AUTHENTICATE, e.getMessage(), null, null));
            }
            return false;
        }
    }

    private void attach(int gen, AgentConnection opened) {
        if (gen != generation || !live) {
            dispose(opened);
            return;
        }
        connection = opened;
        observeSpawn(gen, false);
    }

    private void enterStage(int gen, Stage next) {
        if (gen != generation || !live || stopping) {
            return;
        }
        long now = now();
        publish(frames -> enterStage(frames, next, now));
    }

    private void enterStage(List<ServerMessage> frames, Stage next, long at) {
        stage = next;
        emit(frames, AgentEvents.starting(next), at);
    }

    private void initialized(int gen, AgentHandshake answered) {
        if (gen != generation || !live) {
            return;
        }
        initialized = true;
        handshake = answered;
        boolean missing = !answered.offers(agent.authMethodId());
        if (missing != signInMissing) {
            publish(frames -> {
                signInMissing = missing;
                services.summariesChanged().accept(frames);
            });
        }
    }

    /** Whether this start found the adapter's executable; the card shows a missing one (D9). */
    private void observeSpawn(int gen, boolean missing) {
        if (gen != generation || missing == spawnMissing) {
            return;
        }
        publish(frames -> {
            spawnMissing = missing;
            services.summariesChanged().accept(frames);
        });
    }

    private void sessionOpened(int gen, OpenedSession opened) {
        if (gen != generation || !live || stopping) {
            return;
        }
        long now = now();
        publish(frames -> {
            releaseHeldText(frames, true);
            sessionId = opened.sessionId();
            session = new SessionView();
            session.agentInfo = handshake == null ? null : handshake.agentInfo();
            session.modes = opened.modes();
            session.configOptions = opened.configOptions();
            sessionEnd = null;
            SessionStartedData data = new SessionStartedData();
            data.setSessionId(sessionId);
            data.setAgentInfo(session.agentInfo);
            data.setModes(opened.modes());
            data.setConfigOptions(opened.configOptions());
            emit(frames, AgentEvents.sessionStarted(data), now);
            stage = null;
            setStatus(frames, AgentStatus.READY, now);
            // Updates the adapter sent before its session/new answer was handled (available commands).
            for (EarlyUpdate early : earlyUpdates) {
                apply(frames, early.update(), early.receivedAt());
            }
            earlyUpdates.clear();
        });
        log.info("[{}] Session opened", label);
    }

    // ── sendPrompt ────────────────────────────────────────────────────

    private void handlePrompt(String text, CommandReply reply) {
        if (status != AgentStatus.READY || stopping || !live || connection == null || sessionId == null) {
            reply.reject(RejectReason.INVALID_STATE, "The agent is not ready for a prompt");
            return;
        }
        Run started = new Run(UUID.randomUUID().toString());
        long now = now();
        publish(frames -> {
            // Text held back between runs goes out first, outside the run (no runId of the new one).
            releaseHeldText(frames, true);
            run = started;
            prompt = AdapterValues.text(text);
            lastRun = null;
            lastRunToolCalls = Map.of();
            emit(frames, AgentEvents.runStarted(started.runId, prompt), now);
            setStatus(frames, AgentStatus.RUNNING, now);
        });
        int gen = generation;
        connection.prompt(sessionId, text).whenComplete((stopReason, error) ->
                post(() -> promptAnswered(gen, started.runId, stopReason, error == null ? null : unwrap(error))));
    }

    private void promptAnswered(int gen, String runId, String stopReason, AgentCallException error) {
        if (gen != generation || run == null || !run.runId.equals(runId)) {
            return;
        }
        if (stopping) {
            // stopAgent cancelled this run and waits for its answer: record it, then end the process.
            if (live) {
                run.answered = true;
                run.stopReason = error == null ? stopReason : null;
                finishStop(gen);
            }
            return;
        }
        if (!live) {
            return;
        }
        if (error != null && error.kind() == AgentCallException.Kind.CLOSED) {
            // The process is gone; its exit ends the run as failed (PROCESS_EXITED), not this answer.
            connectionLost(gen);
            return;
        }
        long now = now();
        publish(frames -> {
            RunOutcome outcome;
            ErrorCode errorCode = null;
            if (error != null) {
                outcome = RunOutcome.FAILED;
                errorCode = error.isAuthenticationRequired() ? ErrorCode.AUTH_REQUIRED : ErrorCode.PROMPT_FAILED;
                log.warn("[{}] session/prompt failed: {}", label, error.getMessage());
            } else {
                outcome = outcome(stopReason);
            }
            cancelPendingPermissions(frames, now);
            endRun(frames, outcome, error == null ? stopReason : null, errorCode, now);
            setStatus(frames, AgentStatus.READY, now);
        });
    }

    // ── Adapter messages ──────────────────────────────────────────────

    private AgentConnectionListener listener(int gen, AuthStatusReports reports) {
        return new AgentConnectionListener() {
            @Override
            public void onUpdate(String updateSessionId, AgentUpdate update, long receivedAt) {
                post(() -> updated(gen, updateSessionId, update, receivedAt));
            }

            @Override
            public void onPermissionRequest(PermissionAsk ask, long receivedAt, CompletableFuture<PermissionAnswer> answer) {
                if (!post(() -> permissionRequested(gen, ask, receivedAt, answer))) {
                    answer.complete(new PermissionAnswer.Cancelled());
                }
            }

            @Override
            public void onAuthStatus(AuthStatus reported, long receivedAt) {
                // Queued before the start steps go on, so the event comes before the stage it decides.
                post(() -> authStatusReported(gen, reported, receivedAt));
                reports.reported(reported);
            }

            @Override
            public void onProtocolError(String detail, long receivedAt) {
                post(() -> protocolError(gen, detail));
            }

            @Override
            public void onExit(Integer exitCode, String detail, long receivedAt) {
                post(() -> exited(gen, exitCode));
            }
        };
    }

    private void updated(int gen, String updateSessionId, AgentUpdate update, long receivedAt) {
        if (gen != generation || !live) {
            return;
        }
        if (sessionId == null) {
            if (earlyUpdates.size() < EARLY_UPDATE_LIMIT) {
                earlyUpdates.add(new EarlyUpdate(update, receivedAt));
            }
            return;
        }
        if (!sessionId.equals(updateSessionId)) {
            log.warn("[{}] Dropped a session/update for another session", label);
            return;
        }
        publish(frames -> apply(frames, update, receivedAt));
    }

    /** One session/update as its content event (ADR 0005 kind table), and into the snapshot state. */
    private void apply(List<ServerMessage> frames, AgentUpdate update, long at) {
        switch (update) {
            case AgentUpdate.Chunk chunk -> emitChunk(frames, chunk, at);
            case AgentUpdate.ToolCallReport report -> emitToolCall(frames, report, at);
            case AgentUpdate.Plan plan -> {
                session.plan = plan.entries();
                emit(frames, AgentEvents.plan(plan.entries()), at);
            }
            case AgentUpdate.AvailableCommands commands -> {
                session.availableCommands = commands.commands();
                emit(frames, AgentEvents.availableCommands(commands.commands()), at);
            }
            case AgentUpdate.CurrentMode mode -> {
                session.currentModeId = mode.currentModeId();
                emit(frames, AgentEvents.currentMode(mode.currentModeId()), at);
            }
            case AgentUpdate.ConfigOptions options -> {
                session.configOptions = options.configOptions();
                emit(frames, AgentEvents.configOptions(options.configOptions()), at);
            }
            case AgentUpdate.SessionInfo info -> {
                session.sessionInfo = info.info();
                emit(frames, AgentEvents.sessionInfo(info.info()), at);
            }
            case AgentUpdate.Usage usage -> {
                session.usage = usage.usage();
                emit(frames, AgentEvents.usage(usage.usage()), at);
            }
            case AgentUpdate.Unknown unknown -> emit(frames, AgentEvents.unknown(unknown.type()), at);
        }
    }

    private void permissionRequested(int gen, PermissionAsk ask, long at, CompletableFuture<PermissionAnswer> answer) {
        if (gen != generation || !live) {
            answer.complete(new PermissionAnswer.Cancelled());
            return;
        }
        ToolCallState current = run != null ? run.toolCalls.get(ask.toolCall().toolCallId()) : null;
        PermissionRequestedData data = new PermissionRequestedData();
        data.setToolCall(ToolCalls.merge(current, ask.toolCall()));
        data.setOptions(ask.options());
        if (stopping || status == AgentStatus.CANCELLING || run == null || sessionId == null) {
            // ACP Prompt Turn: a request of a cancelled turn is answered cancelled at once; one outside a
            // run has no run to belong to. The request is still shown, with its end.
            answer.complete(new PermissionAnswer.Cancelled());
            data.setPermissionId(UUID.randomUUID().toString());
            publish(frames -> {
                emit(frames, AgentEvents.permissionRequested(data), at);
                emit(frames, AgentEvents.permissionResolved(data.getPermissionId(), PermissionOutcome.CANCELLED, null), at);
            });
            return;
        }
        String permissionId = services.broker().register(agent.agentId(), answer);
        data.setPermissionId(permissionId);
        publish(frames -> {
            pendingPermissions.put(permissionId, data);
            emit(frames, AgentEvents.permissionRequested(data), at);
            if (status == AgentStatus.RUNNING) {
                setStatus(frames, AgentStatus.AWAITING_PERMISSION, at);
            }
        });
        // The adapter can withdraw its request ($/cancel_request): the SDK then cancels the answer.
        answer.whenComplete((ignored, error) -> {
            if (answer.isCancelled()) {
                post(() -> permissionWithdrawn(gen, permissionId));
            }
        });
    }

    private void handleRespond(String permissionId, String optionId, CommandReply reply) {
        PermissionRequestedData pending = pendingPermissions.get(permissionId);
        if (pending == null) {
            reply.reject(RejectReason.UNKNOWN_PERMISSION, "No such pending permission request", agent.agentId());
            return;
        }
        boolean offered = pending.getOptions().stream().anyMatch(option -> option.getOptionId().equals(optionId));
        if (!offered) {
            reply.reject(RejectReason.INVALID_MESSAGE, "optionId is not one of the request's options", agent.agentId());
            return;
        }
        if (!services.broker().answer(permissionId, new PermissionAnswer.Selected(optionId))) {
            // The adapter withdrew the request before this answer; its withdrawal is queued and closes it.
            reply.reject(RejectReason.UNKNOWN_PERMISSION, "The adapter withdrew this permission request",
                    agent.agentId());
            return;
        }
        long now = now();
        publish(frames -> {
            pendingPermissions.remove(permissionId);
            emit(frames, AgentEvents.permissionResolved(permissionId, PermissionOutcome.SELECTED, optionId), now);
            if (pendingPermissions.isEmpty() && status == AgentStatus.AWAITING_PERMISSION) {
                setStatus(frames, AgentStatus.RUNNING, now);
            }
        });
    }

    /**
     * The adapter withdrew a pending request (ADR 0005 decision 7): it is closed {@code cancelled}, as
     * the adapter will never read an answer to it, and the run goes on.
     */
    private void permissionWithdrawn(int gen, String permissionId) {
        services.broker().forget(permissionId);
        if (gen != generation || !live || !pendingPermissions.containsKey(permissionId)) {
            return;
        }
        long now = now();
        publish(frames -> {
            pendingPermissions.remove(permissionId);
            emit(frames, AgentEvents.permissionResolved(permissionId, PermissionOutcome.CANCELLED, null), now);
            if (pendingPermissions.isEmpty() && status == AgentStatus.AWAITING_PERMISSION) {
                setStatus(frames, AgentStatus.RUNNING, now);
            }
        });
        log.info("[{}] The adapter withdrew a permission request", label);
    }

    private void authStatusReported(int gen, AuthStatus reported, long at) {
        if (gen != generation || !live) {
            return;
        }
        publish(frames -> {
            authStatus = reported;
            authRequired = NOT_LOGGED_IN.equals(reported.getKind());
            emit(frames, AgentEvents.authStatus(reported), at);
            services.summariesChanged().accept(frames);
        });
    }

    private void protocolError(int gen, String detail) {
        if (gen != generation || !live) {
            return;
        }
        if (stopping) {
            finishStop(gen);
            return;
        }
        if (!initialized) {
            // stdout is not ACP before initialize answered: the adapter did not start (ADR 0003).
            fail(gen, ErrorCode.ADAPTER_START_FAILED, Stage.SPAWN, detail, SessionEndReason.ABORTED, null);
            return;
        }
        fail(gen, ErrorCode.PROTOCOL_ERROR, currentStage(), detail, SessionEndReason.ABORTED, null);
    }

    private void exited(int gen, Integer exitCode) {
        if (gen != generation || !live) {
            return;
        }
        if (stopping) {
            // An exit while stopping is the stop's end, whatever its code (D13).
            finishStop(gen);
            return;
        }
        String message = "The adapter process exited" + (exitCode == null ? "" : " with code " + exitCode);
        if (!initialized) {
            fail(gen, ErrorCode.ADAPTER_START_FAILED, Stage.SPAWN, message, SessionEndReason.EXITED, exitCode);
            return;
        }
        fail(gen, ErrorCode.PROCESS_EXITED, currentStage(), message, SessionEndReason.EXITED, exitCode);
    }

    /** A call found the connection gone; the exit report normally follows and wins. */
    private void connectionLost(int gen) {
        services.scheduler().schedule(() -> post(() -> exited(gen, null)), CONNECTION_LOST_GRACE.toMillis(),
                TimeUnit.MILLISECONDS);
    }

    /**
     * Ends the agent in {@code error} (ADR 0005 transition table): kills the process tree first, so that
     * {@code error} means the process is gone, then publishes the run, permission and session ends and
     * the error.
     *
     * @param reason the session end, when a session was open
     */
    private void fail(int gen, ErrorCode code, Stage failedStage, String message, SessionEndReason reason,
            Integer exitCode) {
        if (gen != generation || !live) {
            return;
        }
        live = false;
        stopping = false;
        AgentConnection current = connection;
        connection = null;
        if (current != null) {
            dispose(current);
        }
        long now = now();
        publish(frames -> failFrames(frames, code, failedStage, message, reason, exitCode, now));
        log.warn("[{}] {} at {}: {}", label, code, failedStage, AdapterValues.text(message));
    }

    private void failFrames(List<ServerMessage> frames, ErrorCode code, Stage failedStage, String message,
            SessionEndReason reason, Integer exitCode, long at) {
        cancelPendingPermissions(frames, at);
        if (run != null) {
            ErrorCode runError = code == ErrorCode.PROTOCOL_ERROR ? ErrorCode.PROTOCOL_ERROR : ErrorCode.PROCESS_EXITED;
            endRun(frames, RunOutcome.FAILED, null, runError, at);
        }
        endSession(frames, reason == null ? SessionEndReason.EXITED : reason, exitCode, at);
        lastError = AgentEvents.error(code, failedStage, message);
        emit(frames, AgentEvents.agentError(lastError), at);
        stage = null;
        earlyUpdates.clear();
        setStatus(frames, AgentStatus.ERROR, at);
        services.summariesChanged().accept(frames);
    }

    // ── stopAgent ─────────────────────────────────────────────────────

    private void handleStop(CommandReply reply) {
        if (status == AgentStatus.OFFLINE || status == AgentStatus.ERROR) {
            reply.reject(RejectReason.INVALID_STATE, "The agent is not running");
            return;
        }
        if (stopping) {
            return;
        }
        stopping = true;
        int gen = generation;
        log.info("[{}] Stopping", label);
        if (run != null && live && connection != null && sessionId != null) {
            // ADR 0003 decision 7: cancel the turn, answer pending permission requests cancelled, and wait a
            // bounded time for the stop reason; then end the process either way.
            long now = now();
            publish(frames -> {
                cancelPendingPermissions(frames, now);
                setStatus(frames, AgentStatus.CANCELLING, now);
            });
            connection.cancel(sessionId);
            services.scheduler().schedule(() -> post(() -> finishStop(gen)),
                    services.timeouts().stopCancelWait().toMillis(), TimeUnit.MILLISECONDS);
            return;
        }
        finishStop(gen);
    }

    /** Ends the stopped process off the mailbox: the close blocks (stdin EOF, exit, tree kill). */
    private void finishStop(int gen) {
        if (gen != generation || !stopping) {
            return;
        }
        boolean wasLive = live;
        live = false;
        AgentConnection current = connection;
        connection = null;
        if (!wasLive) {
            // finishStop ran already; the close is under way.
            return;
        }
        Thread.ofVirtual().name("agent-stop-" + label).start(() -> {
            if (current != null) {
                closeAndForget(current);
            }
            post(() -> stopped(gen));
        });
    }

    private void stopped(int gen) {
        if (gen != generation || !stopping) {
            return;
        }
        stopping = false;
        long now = now();
        publish(frames -> {
            cancelPendingPermissions(frames, now);
            if (run != null) {
                // The stop reason if the cancelled turn answered in time, else failed without an error code.
                RunOutcome outcome = run.answered && run.stopReason != null ? outcome(run.stopReason) : RunOutcome.FAILED;
                endRun(frames, outcome, run.answered ? run.stopReason : null, null, now);
            }
            endSession(frames, SessionEndReason.STOPPED, null, now);
            stage = null;
            earlyUpdates.clear();
            setStatus(frames, AgentStatus.OFFLINE, now);
        });
        log.info("[{}] Stopped", label);
    }

    // ── checkConnection ───────────────────────────────────────────────

    private void handleCheck(CommandReply reply) {
        if (checking || stopping || (status != AgentStatus.OFFLINE && status != AgentStatus.ERROR)) {
            reply.reject(RejectReason.INVALID_STATE, "A connection check runs only while the agent is offline or in error");
            return;
        }
        checking = true;
        Thread.ofVirtual().name("agent-check-" + label).start(() -> {
            CheckResult result;
            try {
                result = runCheck();
            } catch (RuntimeException e) {
                log.error("[{}] The connection check failed", label, e);
                result = CheckResult.failed(null, List.of(), null,
                        AgentEvents.error(ErrorCode.CHECK_UNAVAILABLE, Stage.CHECK, "The check could not run"));
            }
            if (result == null) {
                return;
            }
            CheckResult done = result;
            post(() -> checked(done));
        });
    }

    /**
     * ADR 0004 decision 6: spawn and {@code initialize} only, never a session. The result says what the
     * check observed; the readiness it reports is {@link #readiness()} with those observations.
     *
     * @return {@code null} when the runtime shut down during the check
     */
    private CheckResult runCheck() {
        if (!HarnessCatalog.supports(agent)) {
            return CheckResult.failed(null, List.of(), null, AgentEvents.error(ErrorCode.MODEL_INCOMPATIBLE,
                    Stage.CONFIG, "The harness does not run with this model provider and auth method"));
        }
        HarnessLaunch launch = services.launcher().resolve(agent.harnessId());
        if (launch instanceof HarnessLaunch.Unavailable unavailable) {
            return CheckResult.failed(null, List.of(), null,
                    AgentEvents.error(ErrorCode.EXECUTABLE_MISSING, Stage.SPAWN, unavailable.reason()));
        }
        AuthStatusReports reports = new AuthStatusReports();
        CompletableFuture<CheckBreak> broken = new CompletableFuture<>();
        AgentConnection opened;
        try {
            opened = open(launchSpec((HarnessLaunch.Ready) launch), new CheckListener(reports, broken));
        } catch (AgentCallException e) {
            boolean missing = e.kind() == AgentCallException.Kind.EXECUTABLE_MISSING;
            return CheckResult.failed(missing, List.of(), null,
                    AgentEvents.error(missing ? ErrorCode.EXECUTABLE_MISSING : ErrorCode.ADAPTER_START_FAILED,
                            Stage.SPAWN, e.getMessage()));
        }
        if (opened == null) {
            return null;
        }
        try {
            AgentHandshake answered;
            try {
                answered = opened.initialize(services.timeouts().initialize());
            } catch (AgentCallException e) {
                AgentErrorData error = switch (e.kind()) {
                    case CLOSED -> AgentEvents.error(ErrorCode.ADAPTER_START_FAILED, Stage.SPAWN,
                            "The adapter process exited before it answered initialize");
                    case TIMEOUT -> AgentEvents.error(ErrorCode.ACP_INIT_FAILED, Stage.INITIALIZE,
                            "initialize got no answer within " + services.timeouts().initialize().toSeconds() + " s");
                    default -> AgentEvents.error(ErrorCode.ACP_INIT_FAILED, Stage.INITIALIZE, e.getMessage());
                };
                if (broken.isDone()) {
                    // Before initialize answered, an exit or a non-ACP stdout is a failed start (ADR 0003).
                    error = AgentEvents.error(ErrorCode.ADAPTER_START_FAILED, Stage.SPAWN, broken.getNow(null).detail());
                }
                return CheckResult.failed(false, List.of(), null, error);
            }
            if (answered.protocolVersion() != PROTOCOL_VERSION) {
                return CheckResult.failed(false, visibleMethods(answered), null,
                        AgentEvents.error(ErrorCode.PROTOCOL_UNSUPPORTED, Stage.INITIALIZE, "The adapter answered protocol "
                                + "version " + answered.protocolVersion() + "; this runtime speaks " + PROTOCOL_VERSION));
            }
            if (answered.pushesAuthStatus()) {
                // The first status, or the adapter breaking while it is awaited, ends the wait.
                await(CompletableFuture.anyOf(reports.first, broken), services.timeouts().authStatusWait());
            }
            AuthStatus reported = reports.first.getNow(null);
            boolean offers = answered.offers(agent.authMethodId());
            if (broken.isDone()) {
                // A check that ends with the adapter gone or speaking no ACP is no healthy connection.
                CheckBreak end = broken.getNow(null);
                return new CheckResult(false, offers, visibleMethods(answered), reported, AgentEvents.error(
                        end.exited() ? ErrorCode.PROCESS_EXITED : ErrorCode.PROTOCOL_ERROR, Stage.INITIALIZE,
                        end.detail()));
            }
            if (!offers) {
                return new CheckResult(false, false, visibleMethods(answered), reported, AgentEvents.error(
                        ErrorCode.AUTH_FAILED, Stage.AUTHENTICATE,
                        "The adapter does not offer the '" + agent.authMethodId() + "' sign-in"));
            }
            return new CheckResult(false, true, visibleMethods(answered), reported, null);
        } finally {
            closeAndForget(opened);
        }
    }

    private void checked(CheckResult result) {
        checking = false;
        Readiness[] reported = new Readiness[1];
        publish(frames -> {
            if (result.authStatus() != null) {
                authRequired = NOT_LOGGED_IN.equals(result.authStatus().getKind());
            }
            if (result.spawnMissing() != null) {
                spawnMissing = result.spawnMissing();
            }
            if (result.offersSignIn() != null) {
                signInMissing = !result.offersSignIn();
            }
            reported[0] = readiness();
            ConnectionChecked frame = new ConnectionChecked();
            frame.setAgentId(agent.agentId());
            frame.setReadiness(reported[0]);
            frame.setAuthMethods(result.authMethods());
            frame.setAuthStatus(result.authStatus());
            frame.setError(result.error());
            frames.add(frame);
            services.summariesChanged().accept(frames);
        });
        log.info("[{}] Connection checked: {}{}", label, reported[0],
                result.error() == null ? "" : " (" + result.error().getCode() + ")");
    }

    private static List<AuthMethodInfo> visibleMethods(AgentHandshake answered) {
        return answered.authMethods().stream().filter(method -> !HIDDEN_AUTH_METHOD.equals(method.getId())).toList();
    }

    // ── Shared steps ──────────────────────────────────────────────────

    private void endRun(List<ServerMessage> frames, RunOutcome outcome, String stopReason, ErrorCode errorCode, long at) {
        RunEndedData data = new RunEndedData();
        data.setRunId(run.runId);
        data.setOutcome(outcome);
        data.setStopReason(stopReason == null ? null : AdapterValues.text(stopReason));
        data.setErrorCode(errorCode);
        DerivedToolStatus derived = outcome == RunOutcome.CANCELLED ? DerivedToolStatus.CANCELLED
                : DerivedToolStatus.INCOMPLETE;
        List<UnfinishedToolCall> unfinished = new ArrayList<>();
        run.toolCalls.values().stream().filter(call -> !ToolCalls.isFinished(call)).forEach(call -> {
            UnfinishedToolCall open = new UnfinishedToolCall();
            open.setToolCallId(call.getToolCallId());
            open.setDerivedStatus(derived);
            unfinished.add(open);
        });
        data.setUnfinishedToolCalls(unfinished);
        emit(frames, AgentEvents.runEnded(data), at);
        lastRun = data;
        lastRunToolCalls = Map.copyOf(run.toolCalls);
        run = null;
        log.info("[{}] Run ended: {}{}", label, outcome, errorCode == null ? "" : " (" + errorCode + ")");
    }

    /** {@code sessionEnded} only after {@code sessionStarted} (ADR 0005). */
    private void endSession(List<ServerMessage> frames, SessionEndReason reason, Integer exitCode, long at) {
        if (sessionId != null) {
            SessionEndedData data = new SessionEndedData();
            data.setReason(reason);
            data.setExitCode(reason == SessionEndReason.EXITED ? exitCode : null);
            emit(frames, AgentEvents.sessionEnded(data), at);
            sessionEnd = data;
        }
        sessionId = null;
        session = null;
    }

    private void cancelPendingPermissions(List<ServerMessage> frames, long at) {
        for (String permissionId : List.copyOf(pendingPermissions.keySet())) {
            services.broker().answer(permissionId, new PermissionAnswer.Cancelled());
            pendingPermissions.remove(permissionId);
            emit(frames, AgentEvents.permissionResolved(permissionId, PermissionOutcome.CANCELLED, null), at);
        }
    }

    private void setStatus(List<ServerMessage> frames, AgentStatus next, long at) {
        if (next == status) {
            return;
        }
        AgentStatus previous = status;
        status = next;
        emit(frames, AgentEvents.statusChanged(next, previous), at);
    }

    /**
     * Emits an event that is not a text chunk or a piece of command output. The text chunks and the command
     * output held back so far go out first, masked as they stand, so that nothing held passes an event that
     * came after it (ADR 0005 decision 11).
     */
    private void emit(List<ServerMessage> frames, AgentEvent event, long receivedAt) {
        boolean end = endsTextStreams(event);
        releaseHeldText(frames, end);
        releaseHeldOutput(frames, end, null);
        send(frames, event, receivedAt);
    }

    /**
     * A tool call or its update. A piece of command output in it ({@code _meta.terminal_output.data}) is
     * masked across the pieces of its terminal first, which may hold back the start of a secret: the event
     * then carries less output, or none (ADR 0005 decision 11). Other held output stays held for a piece
     * that does not finish its tool call; any other report is like any other event.
     */
    private void emitToolCall(List<ServerMessage> frames, AgentUpdate.ToolCallReport report, long at) {
        Map<String, ToolCallState> calls = run != null ? run.toolCalls : lastRunToolCalls;
        ToolCallState merged = ToolCalls.merge(calls.get(report.toolCallId()), report);
        boolean finished = ToolCalls.isFinished(merged);
        AgentUpdate.TerminalOutput output = report.terminalOutput();
        TerminalSeams.Terminal terminal =
                output == null ? null : new TerminalSeams.Terminal(report.toolCallId(), output.terminalId());
        releaseHeldText(frames, false);
        if (terminal == null || finished) {
            releaseHeldOutput(frames, false, terminal);
        }
        if (terminal != null) {
            String shown = terminals.accept(terminal, output.data(), merged, at);
            if (finished) {
                shown += terminals.end(terminal);
            }
            merged = ToolCalls.withTerminalOutput(merged, shown);
        }
        if (finished) {
            terminals.forget(report.toolCallId());
        }
        if (run != null) {
            run.toolCalls.put(report.toolCallId(), merged);
        }
        send(frames, AgentEvents.toolCall(report.initial(), merged), at);
    }

    /**
     * Sends the command output held back so far, masked as it stands, each in an update event of its tool
     * call, with the envelope of now (as {@link #releaseHeldText}).
     *
     * @param end whether the terminals end here (a run or session boundary)
     * @param except a terminal whose held output the next event carries itself, or {@code null}
     */
    private void releaseHeldOutput(List<ServerMessage> frames, boolean end, TerminalSeams.Terminal except) {
        for (TerminalSeams.Held held : terminals.flush(end, except)) {
            if (run != null && run.toolCalls.containsKey(held.state().getToolCallId())) {
                run.toolCalls.put(held.state().getToolCallId(), held.state());
            }
            send(frames, AgentEvents.toolCall(false, held.state()), held.receivedAt());
        }
    }

    /**
     * Sends the text chunks held back so far, masked as they stand, with the envelope of now (the run and
     * session they arrived in, when called before either changes).
     *
     * @param end whether the text streams end here (a run or session boundary)
     */
    private void releaseHeldText(List<ServerMessage> frames, boolean end) {
        for (ChunkSeams.Text held : seams.flush(end)) {
            send(frames, AgentEvents.chunk(held.kind(), held.messageId(), TEXT, held.text()), held.receivedAt());
        }
    }

    /**
     * A chunk: its text is masked across the seams of its message first, which may hold back the start of
     * a secret (then less text goes out, or no event). A chunk without text is like any other event.
     */
    private void emitChunk(List<ServerMessage> frames, AgentUpdate.Chunk chunk, long receivedAt) {
        if (chunk.text() == null) {
            emit(frames, AgentEvents.chunk(chunk.kind(), chunk.messageId(), chunk.contentType(), null), receivedAt);
            return;
        }
        for (ChunkSeams.Text ready : seams.accept(chunk.kind(), chunk.messageId(), chunk.text(), receivedAt)) {
            send(frames, AgentEvents.chunk(ready.kind(), ready.messageId(), TEXT, ready.text()), ready.receivedAt());
        }
    }

    /** Run and session boundaries end the text streams: a later message never continues an earlier one. */
    private static boolean endsTextStreams(AgentEvent event) {
        return event instanceof RunStartedEvent || event instanceof RunEndedEvent
                || event instanceof SessionStartedEvent || event instanceof SessionEndedEvent
                || event instanceof AgentErrorEvent;
    }

    /** Sets the envelope and queues the frame; {@code seq} counts from 1 per agent (ADR 0005 decision 1). */
    private void send(List<ServerMessage> frames, AgentEvent event, long receivedAt) {
        event.setProjectId(agent.projectId());
        event.setAgentId(agent.agentId());
        event.setSessionId(sessionId);
        event.setRunId(run == null ? null : run.runId);
        event.setSeq(++lastSeq);
        event.setReceivedAt(receivedAt);
        recent.add(event);
        AgentEventMessage frame = new AgentEventMessage();
        frame.setEvent(event);
        frames.add(frame);
    }

    /** The stage an error after {@code initialize} belongs to: the start step, the run, or the session. */
    private Stage currentStage() {
        return switch (status) {
            case STARTING -> stage == null ? Stage.INITIALIZE : stage;
            case RUNNING, AWAITING_PERMISSION, CANCELLING -> Stage.PROMPT;
            default -> Stage.RUNNING;
        };
    }

    private AgentConnectionFactory.LaunchSpec launchSpec(HarnessLaunch.Ready ready) {
        log.info("[{}] Adapter command ({}): {}{}", label, ready.origin(),
                AdapterValues.text(String.join(" ", ready.argv())),
                ready.environment().isEmpty() ? "" : " with " + new TreeMap<>(ready.environment()));
        return new AgentConnectionFactory.LaunchSpec(ready.argv(), projectPath,
                AdapterEnvironment.ofRuntime().with(ready.environment()), "agent " + label,
                services.timeouts().longestCall());
    }

    /**
     * Opens an adapter connection and records it, so that runtime shutdown kills it in any step.
     *
     * @return {@code null} when the runtime shut down meanwhile; the connection is killed then
     */
    private AgentConnection open(AgentConnectionFactory.LaunchSpec spec, AgentConnectionListener listener)
            throws AgentCallException {
        AgentConnection opened = services.connections().open(spec, listener);
        openConnections.add(opened);
        if (shutDown) {
            dispose(opened);
            return null;
        }
        return opened;
    }

    /** Kills a connection's process tree at once and forgets it. */
    private void dispose(AgentConnection opened) {
        opened.kill();
        openConnections.remove(opened);
    }

    /** Ends a connection's process gracefully (blocks, D13) and forgets it. */
    private void closeAndForget(AgentConnection opened) {
        try {
            opened.close(services.timeouts().close());
        } finally {
            openConnections.remove(opened);
        }
    }

    private void publish(Consumer<List<ServerMessage>> change) {
        services.publisher().publish(() -> {
            List<ServerMessage> frames = new ArrayList<>();
            change.accept(frames);
            return frames;
        });
    }

    /** @return whether the task was queued (the mailbox is gone after shutdown) */
    private boolean post(Runnable task) {
        try {
            mailbox.execute(() -> {
                try {
                    task.run();
                } catch (RuntimeException e) {
                    log.error("[{}] Agent task failed", label, e);
                }
            });
            return true;
        } catch (RejectedExecutionException e) {
            return false;
        }
    }

    private long now() {
        return services.clock().millis();
    }

    /** ADR 0005 decision 5. A stop reason ACP v1 does not define ends the run as {@code failed}. */
    static RunOutcome outcome(String stopReason) {
        if (stopReason == null) {
            return RunOutcome.FAILED;
        }
        return switch (stopReason) {
            case "end_turn" -> RunOutcome.COMPLETED;
            case "cancelled" -> RunOutcome.CANCELLED;
            case "refusal" -> RunOutcome.REFUSED;
            case "max_tokens", "max_turn_requests" -> RunOutcome.LIMIT_REACHED;
            default -> RunOutcome.FAILED;
        };
    }

    private static <T> T await(CompletableFuture<T> future, Duration timeout) {
        try {
            return future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException | ExecutionException e) {
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    private static AgentCallException unwrap(Throwable error) {
        Throwable cause = error instanceof CompletionException && error.getCause() != null ? error.getCause() : error;
        return cause instanceof AgentCallException call ? call
                : new AgentCallException(AgentCallException.Kind.FAILED, null, String.valueOf(cause.getMessage()), cause);
    }

    /** One run: {@code session/prompt} once (ADR 0005 decision 3). */
    private static final class Run {

        final String runId;
        final Map<String, ToolCallState> toolCalls = new LinkedHashMap<>();
        boolean answered;
        String stopReason;

        Run(String runId) {
            this.runId = runId;
        }
    }

    /** The latest session-scoped reports of the open session. */
    private static final class SessionView {

        agentsroom.contract.AgentInfo agentInfo;
        Map<String, Object> modes;
        List<Map<String, Object>> configOptions;
        String currentModeId;
        SessionInfoData sessionInfo;
        List<AvailableCommand> availableCommands;
        List<PlanEntry> plan;
        UsageData usage;

        SessionSnapshot snapshot() {
            SessionSnapshot snapshot = new SessionSnapshot();
            snapshot.setAgentInfo(agentInfo);
            snapshot.setModes(modes);
            snapshot.setConfigOptions(configOptions);
            snapshot.setCurrentModeId(currentModeId);
            snapshot.setSessionInfo(sessionInfo);
            snapshot.setAvailableCommands(availableCommands);
            snapshot.setPlan(plan);
            snapshot.setUsage(usage);
            return snapshot;
        }
    }

    private record EarlyUpdate(AgentUpdate update, long receivedAt) {
    }

    /**
     * What a connection check observed.
     *
     * @param spawnMissing whether the adapter's executable was missing; {@code null} when the check did
     *     not get as far as starting it
     * @param offersSignIn whether {@code initialize} offered the configured sign-in method; {@code null}
     *     when it did not answer
     */
    private record CheckResult(Boolean spawnMissing, Boolean offersSignIn, List<AuthMethodInfo> authMethods,
            AuthStatus authStatus, AgentErrorData error) {

        /** A check that ended before {@code initialize} told whether the sign-in is offered. */
        static CheckResult failed(Boolean spawnMissing, List<AuthMethodInfo> authMethods, AuthStatus authStatus,
                AgentErrorData error) {
            return new CheckResult(spawnMissing, null, authMethods, authStatus, error);
        }
    }

    /** The checked adapter ended or wrote something that is not ACP. */
    private record CheckBreak(boolean exited, String detail) {
    }

    /** The auth statuses an adapter pushed: the first, which a start waits for, and the latest. */
    private static final class AuthStatusReports {

        final CompletableFuture<AuthStatus> first = new CompletableFuture<>();
        final AtomicReference<AuthStatus> latest = new AtomicReference<>();

        void reported(AuthStatus status) {
            latest.set(status);
            first.complete(status);
        }
    }

    /** A check reports nothing as events: it only collects the first auth status and a broken stdout. */
    private static final class CheckListener implements AgentConnectionListener {

        private final AuthStatusReports reports;
        private final CompletableFuture<CheckBreak> broken;

        CheckListener(AuthStatusReports reports, CompletableFuture<CheckBreak> broken) {
            this.reports = reports;
            this.broken = broken;
        }

        @Override
        public void onUpdate(String sessionId, AgentUpdate update, long receivedAt) {
        }

        @Override
        public void onPermissionRequest(PermissionAsk ask, long receivedAt, CompletableFuture<PermissionAnswer> answer) {
            answer.complete(new PermissionAnswer.Cancelled());
        }

        @Override
        public void onAuthStatus(AuthStatus status, long receivedAt) {
            reports.reported(status);
        }

        @Override
        public void onProtocolError(String detail, long receivedAt) {
            broken.complete(new CheckBreak(false, detail));
        }

        @Override
        public void onExit(Integer exitCode, String detail, long receivedAt) {
            broken.complete(new CheckBreak(true,
                    "The adapter process exited" + (exitCode == null ? "" : " with code " + exitCode)));
        }
    }
}
