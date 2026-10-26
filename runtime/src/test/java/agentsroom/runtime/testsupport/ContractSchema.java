package agentsroom.runtime.testsupport;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Validates JSON messages against {@code core/asyncapi.yaml} with the contract's own validator,
 * {@code node scripts/check-contract-fixtures.mjs --verdicts <dir>}: the ajv setup of
 * {@code npm run contract:check} (strict mode, the parsed AsyncAPI document, the discriminator).
 *
 * <p>Why node rather than a Java JSON Schema library: with one validator for the fixtures and for what
 * the runtime sends, the two cannot read a keyword differently ({@code if}/{@code then}/{@code else},
 * an untyped {@code not}, the AsyncAPI string {@code discriminator}). A Java validator would need its
 * own YAML loading, {@code $ref} resolution and keyword handling: a second reading of the schema. And
 * node is already a build requirement, since the bindings are generated with it.
 */
public final class ContractSchema {

    /** Gradle passes the repo root in this system property. */
    public static final String REPO_ROOT_PROPERTY = "agentsroom.repoRoot";

    private static final Duration TIMEOUT = Duration.ofSeconds(120);
    private static final JsonMapper JSON = JsonMapper.builder().build();

    /**
     * How one message validated.
     *
     * @param legacy whether it is an inherited pixel-agents frame, which the schema accepts but the
     *     runtime neither sends nor accepts
     * @param errors ajv's errors, empty when it is valid
     */
    public record Verdict(boolean valid, boolean legacy, List<String> errors) {

        /** Valid and not legacy: a frame the runtime may send, or a command it must accept. */
        public boolean isAgentsRoomMessage() {
            return valid && !legacy;
        }
    }

    private ContractSchema() {
    }

    /** The repo root: from Gradle, or else the nearest ancestor of the working directory with the contract. */
    public static Path repoRoot() {
        String property = System.getProperty(REPO_ROOT_PROPERTY);
        if (property != null) {
            return Path.of(property);
        }
        for (Path dir = Path.of("").toAbsolutePath(); dir != null; dir = dir.getParent()) {
            if (Files.isRegularFile(dir.resolve("core/asyncapi.yaml"))) {
                return dir;
            }
        }
        throw new IllegalStateException("No core/asyncapi.yaml above " + Path.of("").toAbsolutePath()
                + "; run the tests through Gradle or set -D" + REPO_ROOT_PROPERTY);
    }

    /** The fixture texts in {@code core/fixtures/<kind>/<side>}, by file name order. */
    public static List<Fixture> fixtures(String kind, String side) {
        Path dir = repoRoot().resolve("core/fixtures").resolve(kind).resolve(side);
        try (Stream<Path> files = Files.list(dir)) {
            List<Fixture> fixtures = new ArrayList<>();
            for (Path file : files.filter(path -> path.toString().endsWith(".json")).sorted().toList()) {
                fixtures.add(new Fixture(file.getFileName().toString(), Files.readString(file, StandardCharsets.UTF_8)));
            }
            return fixtures;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** One fixture file. */
    public record Fixture(String name, String text) {
    }

    /** How each ServerMessage text validates, in order. */
    public static List<Verdict> validateServerMessages(List<String> texts) {
        return validate("server", texts);
    }

    /** How each ClientMessage text validates, in order. */
    public static List<Verdict> validateClientMessages(List<String> texts) {
        return validate("client", texts);
    }

    private static List<Verdict> validate(String side, List<String> texts) {
        Path dir;
        try {
            dir = Files.createTempDirectory("agents-room-contract");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        try {
            Path sideDir = Files.createDirectories(dir.resolve(side));
            for (int i = 0; i < texts.size(); i++) {
                Files.writeString(sideDir.resolve(name(i)), texts.get(i), StandardCharsets.UTF_8);
            }
            JsonNode verdicts = JSON.readTree(runValidator(dir));
            List<Verdict> result = new ArrayList<>();
            for (int i = 0; i < texts.size(); i++) {
                JsonNode verdict = verdicts.get(side + "/" + name(i));
                if (verdict == null) {
                    throw new AssertionError("No verdict for " + side + " message " + i + ": " + texts.get(i));
                }
                List<String> errors = verdict.get("errors").valueStream().map(JsonNode::asString).toList();
                result.add(new Verdict(verdict.get("valid").asBoolean(), verdict.get("legacy").asBoolean(), errors));
            }
            return result;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } finally {
            delete(dir);
        }
    }

    private static String runValidator(Path dir) throws IOException {
        Path out = dir.resolve("verdicts.json");
        Path err = dir.resolve("validator.log");
        ProcessBuilder builder = new ProcessBuilder(
                "node", "scripts/check-contract-fixtures.mjs", "--verdicts", dir.toString())
                .directory(repoRoot().toFile())
                .redirectOutput(out.toFile())
                .redirectError(err.toFile());
        Process node;
        try {
            node = builder.start();
        } catch (IOException e) {
            throw new IllegalStateException("The contract tests need node on the PATH (and npm ci at the repo root)", e);
        }
        try {
            if (!node.waitFor(TIMEOUT.toSeconds(), TimeUnit.SECONDS)) {
                node.descendants().forEach(ProcessHandle::destroyForcibly);
                node.destroyForcibly();
                throw new AssertionError("The contract validator did not finish within " + TIMEOUT);
            }
        } catch (InterruptedException e) {
            node.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted while validating", e);
        }
        if (node.exitValue() != 0) {
            throw new AssertionError("The contract validator failed (exit " + node.exitValue() + "):\n"
                    + Files.readString(err, StandardCharsets.UTF_8));
        }
        return Files.readString(out, StandardCharsets.UTF_8);
    }

    private static String name(int index) {
        return String.format(Locale.ROOT, "%05d.json", index);
    }

    private static void delete(Path dir) {
        try (Stream<Path> paths = Files.walk(dir)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
        } catch (IOException e) {
            // A leftover temp directory is harmless.
        }
    }
}
