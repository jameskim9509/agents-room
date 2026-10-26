package agentsroom.runtime.testsupport;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import tools.jackson.databind.json.JsonMapper;

/**
 * The mock ACP agent of {@code e2e/desktop/fixtures/mock-acp-agent.cjs}, which the runtime starts as the
 * Codex adapter through {@code AGENTS_ROOM_HARNESS_CODEX_COMMAND} (a process-boundary mock, e2e/README.md).
 * A test writes the scenario to a file before it starts the agent; the command names that file.
 */
public final class MockAcpAgent {

    public static final String SCRIPT = "e2e/desktop/fixtures/mock-acp-agent.cjs";

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final boolean WINDOWS =
            System.getProperty("os.name").toLowerCase(Locale.ROOT).startsWith("windows");

    private MockAcpAgent() {
    }

    /** The node executable on the PATH, by absolute path. */
    public static Optional<Path> node() {
        String path = System.getenv("PATH");
        if (path == null) {
            return Optional.empty();
        }
        String name = WINDOWS ? "node.exe" : "node";
        for (String dir : path.split(File.pathSeparator)) {
            try {
                Path candidate = Path.of(dir.strip().replace("\"", "")).resolve(name);
                if (candidate.isAbsolute() && Files.isRegularFile(candidate)) {
                    return Optional.of(candidate);
                }
            } catch (InvalidPathException e) {
                // Not a directory that can hold node.
            }
        }
        return Optional.empty();
    }

    /** The adapter command: node, the mock, and its scenario file. Requires {@link #node()}. */
    public static String command(Path scenarioFile) {
        Path script = ContractSchema.repoRoot().resolve(SCRIPT);
        return JSON.writeValueAsString(List.of(node().orElseThrow().toString(), script.toString(), "--scenario-file",
                scenarioFile.toString()));
    }

    /** Makes the next adapter start play the built-in scenario {@code name}. */
    public static void useScenario(Path scenarioFile, String name) {
        useScenarioJson(scenarioFile, "{\"extends\":\"" + name + "\"}");
    }

    /** Makes the next adapter start play this scenario JSON (see the mock's header for its keys). */
    public static void useScenarioJson(Path scenarioFile, String json) {
        try {
            Files.writeString(scenarioFile, json, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
