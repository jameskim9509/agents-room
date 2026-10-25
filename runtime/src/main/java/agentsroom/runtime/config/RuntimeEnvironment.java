package agentsroom.runtime.config;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * What the desktop shell hands the runtime through environment variables (the shared handshake).
 *
 * @param token the per-launch WebSocket token (32 random bytes, base64url)
 * @param dataDirectory the app data directory; the runtime keeps its own files under {@code runtime/}
 * @param allowedOrigins the {@code Origin} values a renderer may connect from
 */
public record RuntimeEnvironment(String token, Path dataDirectory, Set<String> allowedOrigins) {

    public static final String TOKEN = "AGENTS_ROOM_TOKEN";
    public static final String DATA_DIR = "AGENTS_ROOM_DATA_DIR";
    public static final String ALLOWED_ORIGINS = "AGENTS_ROOM_ALLOWED_ORIGINS";

    /** Used when {@value #ALLOWED_ORIGINS} is not set: the packaged renderer's origin. */
    public static final String DEFAULT_ALLOWED_ORIGIN = "app://bundle";

    /** 32 bytes in base64url without padding. */
    static final int TOKEN_LENGTH = 43;

    private static final Pattern TOKEN_FORMAT = Pattern.compile("[A-Za-z0-9_-]{" + TOKEN_LENGTH + "}");
    private static final String RUNTIME_SUBDIRECTORY = "runtime";

    public RuntimeEnvironment {
        allowedOrigins = Set.copyOf(allowedOrigins);
    }

    /**
     * Reads and checks the variables. Fails fast on a missing or malformed token or a missing data
     * directory, because a runtime without them would either accept almost anyone or write its files
     * somewhere unexpected.
     *
     * @param lookup returns the value of a variable, or {@code null} when it is not set
     * @throws RuntimeEnvironmentException when a required variable is missing or invalid
     */
    public static RuntimeEnvironment from(Function<String, String> lookup) {
        return new RuntimeEnvironment(token(lookup.apply(TOKEN)), dataDirectory(lookup.apply(DATA_DIR)),
                allowedOrigins(lookup.apply(ALLOWED_ORIGINS)));
    }

    /** {@code <data dir>/runtime}: everything the runtime persists lives here (D18). */
    public Path runtimeDirectory() {
        return dataDirectory.resolve(RUNTIME_SUBDIRECTORY);
    }

    /** Never prints the token. */
    @Override
    public String toString() {
        return "RuntimeEnvironment[dataDirectory=" + dataDirectory + ", allowedOrigins=" + allowedOrigins
                + ", token=****]";
    }

    /** The token is the WebSocket's only gate, so a short or hand-picked one is refused. */
    private static String token(String value) {
        if (value == null || value.isBlank()) {
            throw new RuntimeEnvironmentException(
                    TOKEN + " is not set. The desktop shell passes a random token per launch.");
        }
        String token = value.strip();
        if (!TOKEN_FORMAT.matcher(token).matches()) {
            // Never echo the value: it may be the real token with a typo.
            throw new RuntimeEnvironmentException(TOKEN + " must be 32 random bytes in base64url without padding ("
                    + TOKEN_LENGTH + " characters of A-Z, a-z, 0-9, '-' and '_').");
        }
        return token;
    }

    private static Path dataDirectory(String value) {
        if (value == null || value.isBlank()) {
            throw new RuntimeEnvironmentException(
                    DATA_DIR + " is not set. The desktop shell passes the app data directory.");
        }
        Path path;
        try {
            path = Path.of(value.strip());
        } catch (InvalidPathException e) {
            throw new RuntimeEnvironmentException(DATA_DIR + " is not a valid path: " + e.getReason(), e);
        }
        if (!path.isAbsolute()) {
            throw new RuntimeEnvironmentException(DATA_DIR + " must be an absolute path.");
        }
        return path.normalize();
    }

    private static Set<String> allowedOrigins(String value) {
        if (value == null) {
            return Set.of(DEFAULT_ALLOWED_ORIGIN);
        }
        Set<String> origins = new LinkedHashSet<>();
        Arrays.stream(value.split(","))
                .map(String::strip)
                .filter(origin -> !origin.isEmpty())
                .forEach(origins::add);
        return origins;
    }
}
