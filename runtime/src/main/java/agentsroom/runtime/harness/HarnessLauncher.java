package agentsroom.runtime.harness;

import agentsroom.runtime.persistence.RuntimeDataDirectory;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.system.ApplicationHome;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Decides how a harness's ACP adapter is started (D11), and so whether it can be: the install check
 * behind the {@code configured} and {@code executable_missing} readiness (D9).
 *
 * <ol>
 *   <li>{@code AGENTS_ROOM_HARNESS_<ID>_COMMAND} (for {@code codex}: {@value #CODEX_COMMAND}), a JSON
 *       array of strings, is the command line as is. For e2e tests and debugging.
 *   <li>With {@value #E2E}{@code =1} and no override, the launch fails closed: an e2e run never starts
 *       a real adapter by accident.
 *   <li>Otherwise {@code <node> <adapters>/node_modules/@agentclientprotocol/codex-acp/dist/index.js},
 *       the adapter pinned in the non-workspace folder {@code tools/adapters/} ({@code npm run
 *       adapters:install}). {@code node} is {@value #NODE} when the shell sets it, else the
 *       {@code node} executable on the {@code PATH}, always as an absolute path. The adapters folder is
 *       {@value #ADAPTERS_DIR} when set, else {@code tools/adapters} in the nearest folder above the
 *       runtime's jar (or classes) that has one.
 * </ol>
 *
 * <p>The shell passes its own Electron executable as {@value #NODE} and sets {@value #NODE_ELECTRON} to
 * {@code 1} (ADR 0003 decision 12). A launch whose executable is that file, the installed adapter's or
 * an override's, then adds {@code ELECTRON_RUN_AS_NODE=1} to the adapter's environment, so Electron
 * runs as plain Node, and loads {@link ElectronAsNodePreload} first ({@code <electron> --require
 * <preload> ...}). The preload keeps the variable to the adapter's own Node processes: codex-acp starts
 * Codex through {@code process.execPath}, which is Electron again and still runs as Node, while the
 * commands Codex runs never see the variable. A launch on a plain node gets neither.
 *
 * <p>Never {@code npx} and never {@code cmd /c}: Java cannot start {@code npx} on Windows, and cmd.exe
 * parses arguments again (ADR 0003).
 */
@Component
public class HarnessLauncher {

    public static final String CODEX = "codex";

    public static final String CODEX_COMMAND = "AGENTS_ROOM_HARNESS_CODEX_COMMAND";
    public static final String E2E = "AGENTS_ROOM_E2E";
    public static final String NODE = "AGENTS_ROOM_NODE";
    public static final String NODE_ELECTRON = "AGENTS_ROOM_NODE_ELECTRON";
    public static final String ADAPTERS_DIR = "AGENTS_ROOM_ADAPTERS_DIR";

    /** The adapter install folder, relative to the repo root in development. */
    static final String ADAPTERS_FOLDER = "tools/adapters";

    /** Each harness's adapter entry point inside the adapters folder. */
    static final Map<String, String> ADAPTER_ENTRIES =
            Map.of(CODEX, "node_modules/@agentclientprotocol/codex-acp/dist/index.js");

    /** What a launch on the shell's Electron adds to the adapter's environment. */
    static final Map<String, String> RUN_AS_NODE = Map.of("ELECTRON_RUN_AS_NODE", "1");

    /** Node's option that loads a script before the adapter's own code. */
    static final String REQUIRE = "--require";

    private static final Logger log = LoggerFactory.getLogger(HarnessLauncher.class);

    /** How far above the runtime's code location the adapters folder is looked for. */
    private static final int SEARCH_DEPTH = 8;

    private static final boolean WINDOWS =
            System.getProperty("os.name").toLowerCase(Locale.ROOT).startsWith("windows");

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final Function<String, String> lookup;
    private final Path codeLocation;
    private final ElectronAsNodePreload preload;

    /** The last resolution per harness, for the readiness of a snapshot (no disk access there). */
    private final Map<String, Boolean> available = new ConcurrentHashMap<>();

    /**
     * Reads through Spring's {@link Environment}, so tests can set the variables as properties, and
     * falls back to {@link System#getenv(String)}, which on Windows finds {@code Path} for {@code PATH}.
     */
    @Autowired
    HarnessLauncher(Environment environment, RuntimeDataDirectory dataDirectory) {
        this(name -> {
            String value = environment.getProperty(name);
            return value != null ? value : System.getenv(name);
        }, new ApplicationHome(HarnessLauncher.class).getDir().toPath(), new ElectronAsNodePreload(dataDirectory.root()));
    }

    /**
     * @param lookup the runtime's environment variables (and, in tests, properties of the same names)
     * @param codeLocation where to start looking for {@code tools/adapters} when {@value #ADAPTERS_DIR}
     *     is not set
     * @param preload the script a launch on the shell's Electron loads first
     */
    public HarnessLauncher(Function<String, String> lookup, Path codeLocation, ElectronAsNodePreload preload) {
        this.lookup = lookup;
        this.codeLocation = codeLocation;
        this.preload = preload;
    }

    /** The environment variable of a harness's command override, e.g. {@value #CODEX_COMMAND}. */
    public static String commandOverrideVariable(String harnessId) {
        return "AGENTS_ROOM_HARNESS_" + harnessId.toUpperCase(Locale.ROOT).replace('-', '_') + "_COMMAND";
    }

    /** Resolves the launch now (reads the disk) and remembers whether it can start. */
    public HarnessLaunch resolve(String harnessId) {
        HarnessLaunch launch = resolveNow(harnessId);
        available.put(harnessId, launch instanceof HarnessLaunch.Ready);
        return launch;
    }

    /**
     * Whether the last {@link #resolve} found a launch. Resolves once when there was none, so call it
     * outside the publish lock the first time (or {@link #refresh} first).
     */
    public boolean isAvailable(String harnessId) {
        Boolean known = available.get(harnessId);
        return known != null ? known : resolve(harnessId) instanceof HarnessLaunch.Ready;
    }

    /** Resolves every harness this runtime launches again, e.g. before a project opens. */
    public void refresh() {
        ADAPTER_ENTRIES.keySet().forEach(this::resolve);
    }

    private HarnessLaunch resolveNow(String harnessId) {
        String overrideVariable = commandOverrideVariable(harnessId);
        String override = value(overrideVariable);
        if (override != null) {
            return parseOverride(overrideVariable, override);
        }
        if ("1".equals(value(E2E))) {
            return new HarnessLaunch.Unavailable(E2E + "=1 needs the adapter command in " + overrideVariable
                    + "; an e2e run never starts the installed adapter");
        }
        String entry = ADAPTER_ENTRIES.get(harnessId);
        if (entry == null) {
            return new HarnessLaunch.Unavailable("This runtime cannot launch the harness yet");
        }
        Optional<Path> adapters = adaptersFolder();
        if (adapters.isEmpty()) {
            return new HarnessLaunch.Unavailable("No adapter install folder (" + ADAPTERS_FOLDER
                    + ") was found; run npm run adapters:install");
        }
        Path script = adapters.get().resolve(entry);
        if (!Files.isRegularFile(script)) {
            return new HarnessLaunch.Unavailable(
                    "The adapter is not installed (" + script + " is missing); run npm run adapters:install");
        }
        HarnessLaunch node = node();
        if (node instanceof HarnessLaunch.Ready ready) {
            List<String> argv = List.of(ready.argv().getFirst(), script.toAbsolutePath().toString());
            return new HarnessLaunch.Ready(ready.environment().isEmpty() ? argv : asNode(argv),
                    "installed adapter " + ADAPTERS_FOLDER, ready.environment());
        }
        return node;
    }

    private HarnessLaunch parseOverride(String variable, String text) {
        JsonNode argv;
        try {
            argv = JSON.readTree(text);
        } catch (JacksonException e) {
            return new HarnessLaunch.Unavailable(variable + " is not JSON");
        }
        if (argv == null || !argv.isArray() || argv.isEmpty()) {
            return new HarnessLaunch.Unavailable(variable + " must be a non-empty JSON array of strings");
        }
        List<String> command = new ArrayList<>();
        for (JsonNode part : argv) {
            if (!part.isString() || part.stringValue().isEmpty()) {
                return new HarnessLaunch.Unavailable(variable + " must be a non-empty JSON array of strings");
            }
            command.add(part.stringValue());
        }
        Path executable;
        try {
            executable = Path.of(command.getFirst());
        } catch (InvalidPathException e) {
            return new HarnessLaunch.Unavailable(variable + " does not start with a valid executable path");
        }
        if (executable.isAbsolute() && !Files.isRegularFile(executable)) {
            return new HarnessLaunch.Unavailable(variable + " names an executable that does not exist: " + executable);
        }
        return isShellElectron(executable) ? new HarnessLaunch.Ready(asNode(command), variable, RUN_AS_NODE)
                : new HarnessLaunch.Ready(command, variable);
    }

    /**
     * {@code argv} of a launch on the shell's Electron, with the preload right after the executable.
     * Should the data directory refuse the file, the adapter still starts as before, and its children
     * inherit the variable.
     */
    private List<String> asNode(List<String> argv) {
        try {
            List<String> withPreload = new ArrayList<>(argv.size() + 2);
            withPreload.add(argv.getFirst());
            withPreload.add(REQUIRE);
            withPreload.add(preload.file().toString());
            withPreload.addAll(argv.subList(1, argv.size()));
            return withPreload;
        } catch (IOException | RuntimeException e) {
            log.warn("Could not write {}; the adapter's children inherit ELECTRON_RUN_AS_NODE",
                    ElectronAsNodePreload.FILE_NAME, e);
            return argv;
        }
    }

    /**
     * Whether {@code executable} is the shell's Electron: {@value #NODE} while {@value #NODE_ELECTRON} is
     * {@code 1}. Compared as files, so another spelling of the same path (case, separators) counts.
     */
    private boolean isShellElectron(Path executable) {
        String configured = value(NODE);
        if (configured == null || !"1".equals(value(NODE_ELECTRON)) || !executable.isAbsolute()) {
            return false;
        }
        try {
            return Files.isSameFile(executable, Path.of(configured));
        } catch (IOException | InvalidPathException e) {
            return false;
        }
    }

    /** {@value #NODE}, or {@code node} on the {@code PATH}, as an absolute path. */
    private HarnessLaunch node() {
        String configured = value(NODE);
        if (configured != null) {
            Path path;
            try {
                path = Path.of(configured);
            } catch (InvalidPathException e) {
                return new HarnessLaunch.Unavailable(NODE + " is not a valid path");
            }
            if (!path.isAbsolute() || !isExecutable(path)) {
                return new HarnessLaunch.Unavailable(NODE + " does not name a node executable by absolute path");
            }
            return new HarnessLaunch.Ready(List.of(path.toString()), NODE,
                    "1".equals(value(NODE_ELECTRON)) ? RUN_AS_NODE : Map.of());
        }
        String searchPath = value("PATH");
        if (searchPath != null) {
            String executable = WINDOWS ? "node.exe" : "node";
            for (String dir : searchPath.split(File.pathSeparator)) {
                if (dir.isBlank()) {
                    continue;
                }
                try {
                    Path candidate = Path.of(dir.strip().replace("\"", "")).resolve(executable);
                    if (candidate.isAbsolute() && isExecutable(candidate)) {
                        return new HarnessLaunch.Ready(List.of(candidate.toString()), "PATH");
                    }
                } catch (InvalidPathException e) {
                    // A malformed PATH entry names no node.
                }
            }
        }
        return new HarnessLaunch.Unavailable("No node executable: set " + NODE + " or put node on the PATH");
    }

    private Optional<Path> adaptersFolder() {
        String configured = value(ADAPTERS_DIR);
        if (configured != null) {
            try {
                Path path = Path.of(configured);
                return path.isAbsolute() && Files.isDirectory(path) ? Optional.of(path) : Optional.empty();
            } catch (InvalidPathException e) {
                return Optional.empty();
            }
        }
        Path dir = codeLocation.toAbsolutePath();
        for (int depth = 0; dir != null && depth < SEARCH_DEPTH; depth++, dir = dir.getParent()) {
            Path candidate = dir.resolve(ADAPTERS_FOLDER);
            if (Files.isRegularFile(candidate.resolve("package.json"))) {
                return Optional.of(candidate);
            }
        }
        return Optional.empty();
    }

    private static boolean isExecutable(Path path) {
        return Files.isRegularFile(path) && (WINDOWS || Files.isExecutable(path));
    }

    /** An unset or blank variable counts as unset. */
    private String value(String name) {
        String value = lookup.apply(name);
        return value == null || value.isBlank() ? null : value.strip();
    }
}
