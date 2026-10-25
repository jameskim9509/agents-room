package agentsroom.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import agentsroom.runtime.config.RuntimeEnvironment;
import agentsroom.runtime.testsupport.InheritingLauncher;
import agentsroom.runtime.testsupport.TestProcesses;
import agentsroom.runtime.testsupport.TestProcesses.StreamCollector;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/**
 * Runs the real boot jar the way the shell does (the shared handshake) and checks the stdio contract:
 * one ready line on stdout and nothing else, logs on stderr without the token, exit on stdin EOF and
 * on parent exit. Gradle passes the jar path in {@value #BOOT_JAR_PROPERTY}.
 */
class RuntimeJarTests {

    static final String BOOT_JAR_PROPERTY = "agentsroom.runtime.bootJar";

    /** 43 characters, the shape of a real token (32 bytes in base64url). */
    private static final String TOKEN = "jar-test-token-value-xxxxxxxxxxxxxxxxxxxxxx";
    private static final Duration STARTUP = Duration.ofSeconds(60);
    /** RuntimeShutdown's grace period is 5 s; the JVM needs a little more to exit. */
    private static final Duration EXIT = Duration.ofSeconds(15);
    private static final Pattern READY_LINE = Pattern.compile("AGENTS_ROOM_READY \\{\"port\":(\\d+)\\}\n");
    private static final boolean WINDOWS = OS.current() == OS.WINDOWS;

    @TempDir
    Path dataDir;

    @TempDir
    Path logs;

    private String bootJar;
    private final List<ProcessHandle> started = new ArrayList<>();

    @BeforeEach
    void findBootJar() {
        bootJar = System.getProperty(BOOT_JAR_PROPERTY);
        assumeTrue(bootJar != null && Files.isRegularFile(Path.of(bootJar)), "run through Gradle: needs the boot jar");
    }

    @AfterEach
    void killLeftovers() {
        for (ProcessHandle process : started) {
            process.descendants().forEach(ProcessHandle::destroyForcibly);
            process.destroyForcibly();
        }
    }

    @Test
    void announcesReadinessOnStdoutOnlyAndExitsWhenStdinCloses() throws Exception {
        // DEBUG web logging prints request URIs, so the masking of the token is exercised for real.
        Process runtime = start(runtimeCommand(), Map.of("LOGGING_LEVEL_ORG_SPRINGFRAMEWORK_WEB", "DEBUG"));
        StreamCollector stderr = TestProcesses.collect(runtime.getErrorStream());
        InputStream stdout = runtime.getInputStream();

        String readyLine = new String(TestProcesses.readLineBytes(stdout, STARTUP), StandardCharsets.UTF_8);
        Matcher ready = READY_LINE.matcher(readyLine);
        assertThat(ready.matches()).as("ready line %s", readyLine).isTrue();
        String firstFrame = connectAndReadFirstFrame(Integer.parseInt(ready.group(1)));
        assertThat(firstFrame).startsWith("{\"type\":\"snapshot\"");

        long closedAt = System.nanoTime();
        runtime.getOutputStream().close();

        assertThat(runtime.waitFor(EXIT.toSeconds(), TimeUnit.SECONDS)).as("exit after stdin EOF").isTrue();
        Duration exitTime = Duration.ofNanos(System.nanoTime() - closedAt);
        assertThat(runtime.exitValue()).isZero();
        assertThat(exitTime).isLessThan(EXIT);
        assertThat(stdout.readAllBytes()).as("stdout after the ready line").isEmpty();
        String logs = stderr.awaitEnd(EXIT);
        assertThat(logs).contains("Started RuntimeApplication", "Shutting down: stdin closed");
        assertThat(logs).contains("token=****").doesNotContain(TOKEN);
        // The job object calls kernel32; the manifest grants native access without a JVM warning.
        assertThat(logs).doesNotContain("A restricted method");
        if (WINDOWS) {
            assertThat(logs).contains("Child processes end with the runtime");
        }
    }

    @Test
    void refusesToStartWithoutAToken() throws Exception {
        ProcessBuilder builder = runtimeProcess(runtimeCommand(), Map.of());
        builder.environment().remove(RuntimeEnvironment.TOKEN);

        assertRefusesToStart(builder, "AGENTS_ROOM_TOKEN is not set");
    }

    @Test
    void refusesToStartWithAGuessableToken() throws Exception {
        ProcessBuilder builder = runtimeProcess(runtimeCommand(), Map.of(RuntimeEnvironment.TOKEN, "a"));

        assertRefusesToStart(builder, "AGENTS_ROOM_TOKEN must be 32 random bytes in base64url");
    }

    private void assertRefusesToStart(ProcessBuilder builder, String reason) throws Exception {
        Process runtime = track(builder.start());
        StreamCollector stderr = TestProcesses.collect(runtime.getErrorStream());

        assertThat(runtime.waitFor(STARTUP.toSeconds(), TimeUnit.SECONDS)).isTrue();
        assertThat(runtime.exitValue()).isNotZero();
        assertThat(runtime.getInputStream().readAllBytes()).as("stdout").isEmpty();
        assertThat(stderr.awaitEnd(EXIT)).contains(reason);
    }

    @Test
    void exitsWhenItsParentExitsEvenWithStdinOpen() throws Exception {
        // The runtime's parent, a launcher, hands its stdin on and then gets killed, while another
        // process keeps the stdin pipe open: only the parent-exit watch can end the runtime. stderr goes
        // to a file, so that no pipe the JDK closes can end the runtime or cut its log short.
        Path stderrFile = logs.resolve("runtime-stderr.log");
        ProcessBuilder builder = runtimeProcess(parentExitScenario(), Map.of()).redirectError(stderrFile.toFile());
        Process holder = track(builder.start());
        byte[] readyBytes = TestProcesses.readLineBytes(holder.getInputStream(), STARTUP);
        String readyLine = new String(readyBytes, StandardCharsets.UTF_8);
        assertThat(READY_LINE.matcher(readyLine).matches()).as("ready line %s", readyLine).isTrue();
        ProcessHandle launcher = WINDOWS ? holder.toHandle() : onlyJavaChild(holder.toHandle());
        ProcessHandle runtime = onlyJavaChild(launcher);
        // Once the launcher is gone, the runtime is nobody's descendant; clean it up directly.
        started.add(runtime);
        started.addAll(holder.toHandle().descendants().toList());

        // Through the handle: on POSIX, destroying a Process object also closes the pipes it holds.
        launcher.destroyForcibly();

        CompletableFuture<ProcessHandle> exit = runtime.onExit();
        assertThat(exit).succeedsWithin(EXIT);
        assertThat(Files.readString(stderrFile, StandardCharsets.UTF_8)).contains("Shutting down: parent process exited");
    }

    /**
     * The launcher and who holds the runtime's stdin. On Windows the test starts the launcher and holds
     * its stdin pipe itself. On POSIX the JDK closes a child's stdin pipe as soon as that child exits,
     * which would end the runtime through stdin EOF instead. So {@code sh} starts the launcher with a
     * {@code sleep} on the writing end of the pipe: {@code sleep 600 | exec <launcher>}.
     */
    private List<String> parentExitScenario() {
        String[] runtimeArguments = runtimeCommand().toArray(String[]::new);
        List<String> launcher = TestProcesses.javaCommand(InheritingLauncher.class, runtimeArguments);
        if (WINDOWS) {
            return launcher;
        }
        List<String> command = new ArrayList<>(List.of("sh", "-c", "sleep 600 | exec \"$@\"", "sh"));
        command.addAll(launcher);
        return command;
    }

    /** The one java child of {@code parent}. On Windows a console process can also have a conhost.exe child. */
    private static ProcessHandle onlyJavaChild(ProcessHandle parent) {
        List<ProcessHandle> children = parent.children()
                .filter(child -> child.info().command().map(TestProcesses::isJava).orElse(false))
                .toList();
        assertThat(children).as("java children of process %s", parent.pid()).hasSize(1);
        return children.getFirst();
    }

    private List<String> runtimeCommand() {
        return List.of(
                TestProcesses.javaExecutable(),
                "-Dstdout.encoding=UTF-8",
                "-Dstderr.encoding=UTF-8",
                "-Dfile.encoding=UTF-8",
                "-jar",
                bootJar);
    }

    private Process start(List<String> command, Map<String, String> extraEnvironment) throws IOException {
        return track(runtimeProcess(command, extraEnvironment).start());
    }

    /** The shell's environment: token, data directory and allowed origins. */
    private ProcessBuilder runtimeProcess(List<String> command, Map<String, String> extraEnvironment) {
        ProcessBuilder builder = new ProcessBuilder(command);
        Map<String, String> environment = builder.environment();
        environment.put(RuntimeEnvironment.TOKEN, TOKEN);
        environment.put(RuntimeEnvironment.DATA_DIR, dataDir.toString());
        environment.put(RuntimeEnvironment.ALLOWED_ORIGINS, "app://bundle");
        environment.putAll(extraEnvironment);
        return builder;
    }

    private Process track(Process process) {
        started.add(process.toHandle());
        return process;
    }

    private static String connectAndReadFirstFrame(int port) throws Exception {
        CompletableFuture<String> first = new CompletableFuture<>();
        WebSocket socket = HttpClient.newHttpClient()
                .newWebSocketBuilder()
                .buildAsync(URI.create("ws://127.0.0.1:" + port + "/ws?token=" + TOKEN), new WebSocket.Listener() {
                    @Override
                    public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
                        first.complete(data.toString());
                        return null;
                    }
                })
                .get(EXIT.toSeconds(), TimeUnit.SECONDS);
        String frame = first.get(EXIT.toSeconds(), TimeUnit.SECONDS);
        socket.sendClose(WebSocket.NORMAL_CLOSURE, "done").get(EXIT.toSeconds(), TimeUnit.SECONDS);
        return frame;
    }
}
