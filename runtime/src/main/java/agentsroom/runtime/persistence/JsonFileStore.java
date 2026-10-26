package agentsroom.runtime.persistence;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.util.Optional;
import java.util.function.Predicate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Reads and writes the runtime's JSON files (D18).
 *
 * <ul>
 *   <li>Writes are atomic: a temp file in the same directory, flushed to disk, then
 *       {@code Files.move(ATOMIC_MOVE, REPLACE_EXISTING)}. A crash leaves either the old or the new file.
 *   <li>A corrupt file (unparseable, or failing the caller's check) is moved aside to
 *       {@code <name>.corrupt-<epoch ms>} and never overwritten; the caller starts from empty
 *       (architecture.md, Persistence). The recovery dialog is SCRUM-37.
 * </ul>
 */
public final class JsonFileStore {

    static final String CORRUPT_SUFFIX = ".corrupt-";

    private static final Logger log = LoggerFactory.getLogger(JsonFileStore.class);

    private final JsonMapper mapper;
    private final Clock clock;

    public JsonFileStore(Clock clock) {
        this.clock = clock;
        // Its own mapper with explicit settings, independent of Boot's auto-configured one.
        this.mapper = JsonMapper.builder()
                .enable(SerializationFeature.INDENT_OUTPUT)
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .build();
    }

    /**
     * @return the file's content; empty when the file does not exist or was corrupt (then it has been
     *     moved aside)
     * @throws UncheckedIOException when the file exists but cannot be read, or is corrupt and cannot
     *     be moved aside. Either way the caller must not overwrite it.
     */
    public <T> Optional<T> read(Path file, Class<T> type, Predicate<? super T> isValid) {
        byte[] bytes;
        try {
            bytes = Files.readAllBytes(file);
        } catch (NoSuchFileException e) {
            return Optional.empty();
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read " + file, e);
        }
        T value;
        try {
            value = mapper.readValue(bytes, type);
        } catch (JacksonException e) {
            setAside(file, "not valid JSON for " + type.getSimpleName());
            return Optional.empty();
        }
        if (value == null || !isValid.test(value)) {
            setAside(file, "unexpected content for " + type.getSimpleName());
            return Optional.empty();
        }
        return Optional.of(value);
    }

    /** Replaces {@code file} atomically with {@code value} as JSON. Creates missing parent directories. */
    public void write(Path file, Object value) {
        byte[] bytes = mapper.writeValueAsBytes(value);
        Path directory = file.toAbsolutePath().getParent();
        Path temp = null;
        try {
            Files.createDirectories(directory);
            temp = Files.createTempFile(directory, file.getFileName().toString() + ".", ".tmp");
            try (FileChannel channel = FileChannel.open(temp, StandardOpenOption.WRITE)) {
                ByteBuffer buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) {
                    channel.write(buffer);
                }
                channel.force(true);
            }
            Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            temp = null;
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot write " + file, e);
        } finally {
            deleteQuietly(temp);
        }
    }

    private void setAside(Path file, String reason) {
        Path aside = file.resolveSibling(file.getFileName() + CORRUPT_SUFFIX + clock.millis());
        for (int attempt = 1; Files.exists(aside); attempt++) {
            aside = file.resolveSibling(file.getFileName() + CORRUPT_SUFFIX + clock.millis() + "-" + attempt);
        }
        try {
            Files.move(file, aside);
        } catch (IOException e) {
            // Starting from empty would overwrite the corrupt file on the next save and lose it.
            throw new UncheckedIOException(file.getFileName() + " is corrupt (" + reason
                    + ") and could not be moved aside", e);
        }
        log.warn("{} is corrupt ({}); kept it as {} and started from empty", file.getFileName(), reason,
                aside.getFileName());
    }

    private static void deleteQuietly(Path temp) {
        if (temp == null) {
            return;
        }
        try {
            Files.deleteIfExists(temp);
        } catch (IOException e) {
            log.warn("Could not delete temp file {}", temp.getFileName(), e);
        }
    }
}
