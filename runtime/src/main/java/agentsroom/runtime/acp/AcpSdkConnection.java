package agentsroom.runtime.acp;

import agentsroom.runtime.logging.SecretMasker;
import com.agentclientprotocol.sdk.client.AcpAsyncClient;
import com.agentclientprotocol.sdk.client.AcpClient;
import com.agentclientprotocol.sdk.client.transport.AgentParameters;
import com.agentclientprotocol.sdk.error.AcpConnectionException;
import com.agentclientprotocol.sdk.error.AcpTimeoutException;
import com.agentclientprotocol.sdk.json.AcpJsonMapper;
import com.agentclientprotocol.sdk.spec.AcpError;
import com.agentclientprotocol.sdk.spec.AcpSchema;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;
import tools.jackson.core.JacksonException;

/**
 * An {@link AgentConnection} over the official ACP Java SDK (ADR 0003 decisions 2, 3): one
 * {@link AcpAsyncClient} on one {@link ProjectStdioTransport}, so one adapter process.
 *
 * <ul>
 *   <li>The SDK handlers run on its inbound thread and only translate and hand off to the listener.
 *   <li>The adapter's stderr goes, masked, to the runtime log with the agent's prefix (ADR 0003 decision
 *       9) on the logger {@value #STDERR_LOGGER}.
 *   <li>The SDK has no getter for the process, so the new child is found by comparing the runtime's
 *       children before and after the start, with every start of this class behind one lock (D13).
 *   <li>An exit, a protocol error or a late message after {@link #close} or {@link #kill} is never
 *       reported: the runtime asked for the end.
 * </ul>
 */
final class AcpSdkConnection implements AgentConnection {

    /** The logger of the adapters' stderr lines. */
    static final String STDERR_LOGGER = "agentsroom.runtime.acp.adapter-stderr";

    static final String AUTH_STATUS_UPDATE = "_auth/status_update";

    /** ADR 0003 capability table: no fs, no terminal, no terminal auth; structured shell output. */
    static final AcpSchema.ClientCapabilities CAPABILITIES = AcpSchema.ClientCapabilities.builder()
            .fs(new AcpSchema.FileSystemCapability(false, false))
            .terminal(false)
            .auth(new AcpSchema.AuthCapabilities(false))
            .meta(Map.of("terminal_output", true))
            .build();

    private static final Logger log = LoggerFactory.getLogger(AcpSdkConnection.class);
    private static final Logger stderr = LoggerFactory.getLogger(STDERR_LOGGER);

    private static final Pattern EXIT_CODE = Pattern.compile("exited with code (-?\\d+)");
    private static final Pattern WINDOWS_ERROR = Pattern.compile("error=(\\d+)");
    private static final Set<String> MISSING_EXECUTABLE_ERRORS = Set.of("2", "3");

    /** One start at a time, so the before/after comparison of the children finds the right process. */
    private static final Object SPAWN_LOCK = new Object();

    private final String label;
    private final AcpAsyncClient client;
    private final AcpTranslator translator;
    private final ProcessHandle process;
    private final ProcessTreeWatch tree;
    private final AtomicBoolean ending;
    private final AtomicBoolean killed = new AtomicBoolean();

    private AcpSdkConnection(String label, AcpAsyncClient client, AcpTranslator translator, ProcessHandle process,
            AtomicBoolean ending) {
        this.label = label;
        this.client = client;
        this.translator = translator;
        this.process = process;
        this.tree = new ProcessTreeWatch(process);
        this.ending = ending;
    }

    static AcpSdkConnection open(AgentConnectionFactory.LaunchSpec spec, AgentConnectionListener listener, Clock clock,
            AcpJsonMapper mapper, String clientVersion) throws AgentCallException {
        List<String> argv = spec.argv();
        checkStartable(argv.getFirst(), spec.cwd());
        AgentParameters parameters = AgentParameters.builder(argv.getFirst())
                .args(argv.subList(1, argv.size()))
                .inheritEnvironment(false)
                .env(spec.environment().variables())
                .build();
        if (!spec.environment().withheldSecrets().isEmpty()) {
            // Names only (ADR 0004 decision 5); the env map itself is never logged.
            log.info("[{}] Not passed to the adapter: {}", spec.label(), spec.environment().withheldSecrets());
        }
        AcpTranslator translator = new AcpTranslator(mapper);
        AtomicBoolean ending = new AtomicBoolean();
        AtomicBoolean protocolErrorReported = new AtomicBoolean();

        ProjectStdioTransport transport = new ProjectStdioTransport(parameters, mapper, spec.cwd());
        String label = spec.label();
        transport.setStdErrorHandler(line -> stderr.info("[{}] {}", label, SecretMasker.mask(line)));
        transport.setExceptionHandler(error -> {
            if (ending.get()) {
                return;
            }
            if (!isUnreadableLine(error)) {
                // A broken pipe or a failed write: the end of the process, which awaitTermination reports.
                log.debug("[{}] Adapter stream error: {}", label, error.toString());
                return;
            }
            if (protocolErrorReported.compareAndSet(false, true)) {
                listener.onProtocolError("The adapter wrote a line on stdout that is not an ACP JSON-RPC message ("
                        + error.getClass().getSimpleName() + ")", clock.millis());
            }
        });

        AcpClient.AsyncSpec builder = AcpClient.async(transport)
                .requestTimeout(spec.longestCall().plusSeconds(30))
                .clientInfo(new AcpSchema.Implementation("agents-room", clientVersion, "agents-room", null))
                .clientCapabilities(CAPABILITIES)
                .sessionUpdateHandler(notification -> {
                    long receivedAt = clock.millis();
                    if (!ending.get()) {
                        try {
                            listener.onUpdate(notification.sessionId(), translator.update(notification.update()),
                                    receivedAt);
                        } catch (RuntimeException e) {
                            log.error("[{}] Could not handle a session/update", label, e);
                        }
                    }
                    return Mono.empty();
                })
                .requestPermissionHandler(request -> {
                    long receivedAt = clock.millis();
                    CompletableFuture<PermissionAnswer> answer = new CompletableFuture<>();
                    if (ending.get()) {
                        answer.complete(new PermissionAnswer.Cancelled());
                    } else {
                        try {
                            listener.onPermissionRequest(translator.permission(request), receivedAt, answer);
                        } catch (RuntimeException e) {
                            log.error("[{}] Could not handle a permission request; answering cancelled", label, e);
                            answer.complete(new PermissionAnswer.Cancelled());
                        }
                    }
                    // The adapter can withdraw the request ($/cancel_request), and closing the session
                    // cancels what is still pending: the SDK then cancels this Mono, which cancels the
                    // answer, so the listener's owner learns that no answer will be read.
                    return Mono.fromFuture(answer, true)
                            .doOnCancel(() -> answer.cancel(false))
                            .map(AcpSdkConnection::response);
                })
                .extNotificationHandler(AUTH_STATUS_UPDATE, params -> {
                    long receivedAt = clock.millis();
                    if (!ending.get()) {
                        AcpTranslator.authStatus(params).ifPresent(status -> listener.onAuthStatus(status, receivedAt));
                    }
                    return Mono.empty();
                });

        AcpAsyncClient client;
        ProcessHandle process;
        synchronized (SPAWN_LOCK) {
            Set<ProcessHandle> before = ProcessHandle.current().children().collect(Collectors.toSet());
            try {
                client = builder.build();
            } catch (RuntimeException e) {
                throw startFailure(e, argv.getFirst());
            }
            process = identify(before, argv.getFirst());
        }
        if (process == null) {
            log.warn("[{}] Could not identify the adapter process; its process tree cannot be killed", label);
        } else {
            log.info("[{}] Adapter started (pid {})", label, process.pid());
        }
        AcpSdkConnection connection = new AcpSdkConnection(label, client, translator, process, ending);
        transport.awaitTermination().subscribe(ignored -> {
        }, error -> {
            if (ending.get()) {
                return;
            }
            String detail = SecretMasker.mask(String.valueOf(error.getMessage()));
            log.warn("[{}] Adapter ended without being asked: {}", label, detail);
            connection.tree.killAll();
            listener.onExit(exitCode(detail), detail, clock.millis());
        });
        return connection;
    }

    @Override
    public Optional<ProcessHandle> process() {
        return Optional.ofNullable(process);
    }

    @Override
    public AgentHandshake initialize(Duration timeout) throws AgentCallException {
        AcpSchema.InitializeResponse response = await(client.initialize(), timeout);
        if (response == null) {
            throw new AgentCallException(AgentCallException.Kind.FAILED, "initialize answered nothing");
        }
        tree.collect();
        int version = response.protocolVersion() == null ? -1 : response.protocolVersion();
        List<agentsroom.contract.AuthMethodInfo> methods = response.authMethods() == null ? List.of()
                : response.authMethods().stream().map(AcpTranslator::authMethod).toList();
        boolean pushes = response.agentCapabilities() != null && response.agentCapabilities().meta() != null
                && response.agentCapabilities().meta().containsKey("authStatus");
        return new AgentHandshake(version, AcpTranslator.agentInfo(response.agentInfo()), methods, pushes);
    }

    @Override
    public void authenticate(String methodId, Duration timeout) throws AgentCallException {
        // codex-acp opens the login page from its own process: a browser it starts is in this tree.
        tree.spareBrowsers();
        await(client.authenticate(new AcpSchema.AuthenticateRequest(methodId)), timeout);
    }

    @Override
    public OpenedSession newSession(Path cwd, Duration timeout) throws AgentCallException {
        AcpSchema.NewSessionResponse response =
                await(client.newSession(new AcpSchema.NewSessionRequest(cwd.toString(), List.of())), timeout);
        if (response == null || response.sessionId() == null) {
            throw new AgentCallException(AgentCallException.Kind.FAILED, "session/new answered no sessionId");
        }
        tree.collect();
        return new OpenedSession(response.sessionId(), translator.modes(response.modes()),
                translator.configOptions(response.configOptions()));
    }

    @Override
    public CompletableFuture<String> prompt(String sessionId, String text) {
        CompletableFuture<String> result = new CompletableFuture<>();
        client.prompt(AcpSchema.PromptRequest.text(sessionId, text)).subscribe(
                response -> result.complete(response.stopReason() == null ? null : response.stopReason().value()),
                error -> result.completeExceptionally(classify(error)),
                () -> result.completeExceptionally(
                        new AgentCallException(AgentCallException.Kind.FAILED, "session/prompt answered nothing")));
        return result;
    }

    @Override
    public void cancel(String sessionId) {
        client.cancel(new AcpSchema.CancelNotification(sessionId)).subscribe(ignored -> {
        }, error -> log.debug("[{}] session/cancel not sent: {}", label, error.toString()));
    }

    @Override
    public void close(Duration timeout) {
        if (!ending.compareAndSet(false, true)) {
            return;
        }
        tree.collect();
        try {
            // Closes stdin and gives the adapter a moment to exit (codex-acp exits on stdin EOF).
            client.closeGracefully().block(timeout);
        } catch (RuntimeException e) {
            log.debug("[{}] Graceful close did not finish: {}", label, e.toString());
        }
        if (!tree.killAll()) {
            log.warn("[{}] Some adapter processes were still alive after the kill", label);
        }
        log.info("[{}] Adapter closed", label);
    }

    @Override
    public void kill() {
        ending.set(true);
        // Also during a graceful close that is still under way (runtime shutdown): kill at once.
        if (!killed.compareAndSet(false, true)) {
            return;
        }
        if (!tree.killAll()) {
            log.warn("[{}] Some adapter processes were still alive after the kill", label);
        }
        client.close();
        log.info("[{}] Adapter killed", label);
    }

    private static AcpSchema.RequestPermissionResponse response(PermissionAnswer answer) {
        return switch (answer) {
            case PermissionAnswer.Selected selected ->
                    new AcpSchema.RequestPermissionResponse(new AcpSchema.PermissionSelected(selected.optionId()));
            case PermissionAnswer.Cancelled ignored ->
                    new AcpSchema.RequestPermissionResponse(new AcpSchema.PermissionCancelled());
        };
    }

    /** Waits for a call; the SDK's own timeout is longer, so this one decides. */
    private static <T> T await(Mono<T> call, Duration timeout) throws AgentCallException {
        try {
            return call.timeout(timeout).toFuture().get();
        } catch (ExecutionException e) {
            throw classify(e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AgentCallException(AgentCallException.Kind.CLOSED, "Interrupted while waiting for the adapter");
        }
    }

    static AgentCallException classify(Throwable error) {
        if (error instanceof AgentCallException call) {
            return call;
        }
        if (error instanceof TimeoutException || error instanceof AcpTimeoutException) {
            return new AgentCallException(AgentCallException.Kind.TIMEOUT, null, "No answer in time", error);
        }
        if (error instanceof AcpError acp) {
            return new AgentCallException(AgentCallException.Kind.ERROR_RESPONSE, acp.getCode(),
                    "JSON-RPC error " + acp.getCode() + ": " + SecretMasker.mask(acp.getMessage()), error);
        }
        if (error instanceof AcpConnectionException) {
            return new AgentCallException(AgentCallException.Kind.CLOSED, null,
                    "The connection to the adapter is gone: " + SecretMasker.mask(error.getMessage()), error);
        }
        return new AgentCallException(AgentCallException.Kind.FAILED, null,
                error.getClass().getSimpleName() + ": " + SecretMasker.mask(String.valueOf(error.getMessage())), error);
    }

    /**
     * Whether the transport reports a stdout line it could not read as JSON-RPC. The SDK's JSON mapper
     * wraps the parser's {@link JacksonException} in an {@link IOException}, and a line that is JSON but no
     * JSON-RPC message is an {@link IllegalArgumentException}. A broken stream or a failed write (a plain
     * {@link IOException}, possibly wrapped) is not: the process is ending, and its exit is reported.
     */
    private static boolean isUnreadableLine(Throwable error) {
        if (error instanceof IllegalArgumentException) {
            return true;
        }
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof JacksonException) {
                return true;
            }
        }
        return false;
    }

    /**
     * Refuses a start that cannot work before trying it, so that a missing executable and a missing
     * project folder are told apart (on Linux both fail with error 2).
     */
    private static void checkStartable(String executable, Path cwd) throws AgentCallException {
        if (!Files.isDirectory(cwd)) {
            throw new AgentCallException(AgentCallException.Kind.START_FAILED, "The project folder is gone: " + cwd);
        }
        Path path;
        try {
            path = Path.of(executable);
        } catch (InvalidPathException e) {
            throw new AgentCallException(AgentCallException.Kind.START_FAILED, "The adapter command is not a valid path");
        }
        if (path.isAbsolute() && !Files.isRegularFile(path)) {
            throw new AgentCallException(AgentCallException.Kind.EXECUTABLE_MISSING,
                    "The adapter executable does not exist: " + path);
        }
    }

    private static AgentCallException startFailure(RuntimeException error, String executable) {
        Throwable cause = error;
        while (cause != null && !(cause instanceof IOException)) {
            cause = cause.getCause();
        }
        String detail = cause != null ? cause.getMessage() : error.getMessage();
        Matcher code = WINDOWS_ERROR.matcher(String.valueOf(detail));
        boolean missing = code.find() && MISSING_EXECUTABLE_ERRORS.contains(code.group(1));
        return new AgentCallException(missing ? AgentCallException.Kind.EXECUTABLE_MISSING
                : AgentCallException.Kind.START_FAILED, null,
                "Could not start " + baseName(executable) + ": " + SecretMasker.mask(detail), error);
    }

    /** The new child whose executable has the launched command's file name; the only new child else. */
    private static ProcessHandle identify(Set<ProcessHandle> before, String executable) {
        List<ProcessHandle> started =
                ProcessHandle.current().children().filter(child -> !before.contains(child)).toList();
        String wanted = baseName(executable);
        List<ProcessHandle> matching = started.stream()
                .filter(child -> child.info().command().map(command -> baseName(command).equals(wanted)).orElse(false))
                .toList();
        if (matching.size() == 1) {
            return matching.getFirst();
        }
        return started.size() == 1 ? started.getFirst() : null;
    }

    static String baseName(String command) {
        String normalized = command.replace('\\', '/');
        String name = normalized.substring(normalized.lastIndexOf('/') + 1).toLowerCase(Locale.ROOT);
        return name.endsWith(".exe") ? name.substring(0, name.length() - ".exe".length()) : name;
    }

    private static Integer exitCode(String detail) {
        Matcher matcher = EXIT_CODE.matcher(detail);
        return matcher.find() ? Integer.valueOf(matcher.group(1)) : null;
    }
}
