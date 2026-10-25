package agentsroom.runtime.testsupport;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

/** Starting helper processes from tests. */
public final class TestProcesses {

    private TestProcesses() {
    }

    /** The java executable running the tests (the JDK 25 toolchain). */
    public static String javaExecutable() {
        return ProcessHandle.current().info().command().orElseThrow();
    }

    /** Whether an executable path names a java launcher ({@code java} or {@code java.exe}). */
    public static boolean isJava(String command) {
        String name = Path.of(command).getFileName().toString().toLowerCase(Locale.ROOT);
        return name.equals("java") || name.equals("java.exe");
    }

    /** The directory holding the compiled test support classes; they need nothing but the JDK. */
    public static String testSupportClasspath() {
        return classpathOf(TestProcesses.class);
    }

    /** A class path with the directories or jars that hold {@code classes}. */
    public static String classpathOf(Class<?>... classes) {
        return Arrays.stream(classes)
                .map(TestProcesses::location)
                .distinct()
                .collect(Collectors.joining(File.pathSeparator));
    }

    private static String location(Class<?> type) {
        try {
            return Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
        } catch (URISyntaxException e) {
            throw new IllegalStateException(e);
        }
    }

    /** A command that runs {@code mainClass} from the test support classes. */
    public static List<String> javaCommand(Class<?> mainClass, String... args) {
        List<String> command = new ArrayList<>();
        command.addAll(List.of(javaExecutable(), "-cp", testSupportClasspath(), mainClass.getName()));
        command.addAll(List.of(args));
        return command;
    }

    /**
     * Reads bytes up to and including the first {@code \n}.
     *
     * @throws TimeoutException when no full line arrives in time
     */
    public static byte[] readLineBytes(InputStream in, Duration timeout)
            throws InterruptedException, ExecutionException, TimeoutException {
        CompletableFuture<byte[]> line = CompletableFuture.supplyAsync(() -> {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try {
                int b;
                while ((b = in.read()) != -1) {
                    bytes.write(b);
                    if (b == '\n') {
                        break;
                    }
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            return bytes.toByteArray();
        });
        return line.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
    }

    /** Collects a stream in the background, for stderr. */
    public static StreamCollector collect(InputStream in) {
        return new StreamCollector(in);
    }

    /** Everything a stream produced so far, as UTF-8. */
    public static final class StreamCollector {

        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private final Thread reader;

        StreamCollector(InputStream in) {
            reader = Thread.ofPlatform().daemon().start(() -> {
                byte[] buffer = new byte[4096];
                try {
                    int read;
                    while ((read = in.read(buffer)) != -1) {
                        synchronized (bytes) {
                            bytes.write(buffer, 0, read);
                        }
                    }
                } catch (IOException e) {
                    // The process went away; keep what was read.
                }
            });
        }

        /** Waits for the stream to end (the process exited), then returns everything. */
        public String awaitEnd(Duration timeout) throws InterruptedException {
            reader.join(timeout);
            return text();
        }

        public String text() {
            synchronized (bytes) {
                return bytes.toString(StandardCharsets.UTF_8);
            }
        }
    }
}
