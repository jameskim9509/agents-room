package agentsroom.runtime.harness;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

/** How the Codex adapter command is decided (D11) and so whether Codex is installed (D9). */
class HarnessLauncherTests {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final boolean WINDOWS =
            System.getProperty("os.name").toLowerCase(Locale.ROOT).startsWith("windows");

    @TempDir
    Path root;

    private final Map<String, String> variables = new HashMap<>();
    private Path node;
    private Path adapters;

    @BeforeEach
    void layout() throws IOException {
        Path bin = Files.createDirectories(root.resolve("bin"));
        node = Files.writeString(bin.resolve(WINDOWS ? "node.exe" : "node"), "", StandardCharsets.UTF_8);
        if (!WINDOWS) {
            node.toFile().setExecutable(true);
        }
        adapters = root.resolve("repo").resolve(HarnessLauncher.ADAPTERS_FOLDER);
        variables.put("PATH", root.resolve("elsewhere") + File.pathSeparator + bin);
    }

    @Test
    void theOverrideIsTheCommandLineAsIs() {
        variables.put(HarnessLauncher.CODEX_COMMAND,
                JSON.writeValueAsString(List.of(node.toString(), "mock agent.cjs", "--scenario", "happy")));

        HarnessLaunch launch = launcher(root).resolve(HarnessLauncher.CODEX);

        assertThat(launch).isEqualTo(new HarnessLaunch.Ready(
                List.of(node.toString(), "mock agent.cjs", "--scenario", "happy"), HarnessLauncher.CODEX_COMMAND));

        variables.put(HarnessLauncher.CODEX_COMMAND, "[\"node\", \"mock.cjs\"]");
        assertThat(launcher(root).resolve(HarnessLauncher.CODEX)).as("a bare name is looked up when it starts")
                .isInstanceOf(HarnessLaunch.Ready.class);
    }

    @Test
    void aMalformedOverrideIsUnavailable() {
        String missing = JSON.writeValueAsString(List.of(root.resolve("missing/node.exe").toString(), "mock.cjs"));
        for (String override : List.of("node mock.cjs", "[]", "[\"node\", 3]", "{\"argv\": []}", "[\"\"]", missing)) {
            variables.put(HarnessLauncher.CODEX_COMMAND, override);

            assertThat(launcher(root).resolve(HarnessLauncher.CODEX)).as(override)
                    .isInstanceOf(HarnessLaunch.Unavailable.class);
        }
    }

    @Test
    void anE2eRunWithoutAnOverrideFailsClosed() throws IOException {
        installAdapter();
        variables.put(HarnessLauncher.E2E, "1");

        HarnessLaunch launch = launcher(root.resolve("repo/runtime/build/libs")).resolve(HarnessLauncher.CODEX);

        assertThat(launch).isInstanceOfSatisfying(HarnessLaunch.Unavailable.class,
                unavailable -> assertThat(unavailable.reason()).contains(HarnessLauncher.CODEX_COMMAND));
    }

    @Test
    void theInstalledAdapterRunsOnNodeFromThePathFoundAboveTheJar() throws IOException {
        Path script = installAdapter();

        HarnessLauncher launcher = launcher(Files.createDirectories(root.resolve("repo/runtime/build/libs")));

        assertThat(launcher.resolve(HarnessLauncher.CODEX))
                .isEqualTo(new HarnessLaunch.Ready(List.of(node.toString(), script.toString()), "installed adapter "
                        + HarnessLauncher.ADAPTERS_FOLDER));
        assertThat(launcher.isAvailable(HarnessLauncher.CODEX)).isTrue();
    }

    @Test
    void theShellsNodeAndAdaptersFolderWin() throws IOException {
        Path script = installAdapter();
        Path otherNode = Files.writeString(root.resolve(WINDOWS ? "shell-node.exe" : "shell-node"), "",
                StandardCharsets.UTF_8);
        otherNode.toFile().setExecutable(true);
        variables.put(HarnessLauncher.NODE, otherNode.toString());
        variables.put(HarnessLauncher.ADAPTERS_DIR, adapters.toString());

        HarnessLaunch launch = launcher(root.resolve("nowhere")).resolve(HarnessLauncher.CODEX);

        assertThat(launch).isInstanceOfSatisfying(HarnessLaunch.Ready.class,
                ready -> assertThat(ready.argv()).containsExactly(otherNode.toString(), script.toString()));
    }

    @Test
    void theShellsElectronRunsAsNode() throws IOException {
        Path script = installAdapter();
        Path electron = Files.writeString(root.resolve(WINDOWS ? "electron.exe" : "electron"), "",
                StandardCharsets.UTF_8);
        electron.toFile().setExecutable(true);
        variables.put(HarnessLauncher.NODE, electron.toString());
        variables.put(HarnessLauncher.ADAPTERS_DIR, adapters.toString());

        HarnessLauncher launcher = launcher(root.resolve("nowhere"));
        assertThat(launcher.resolve(HarnessLauncher.CODEX)).as("a plain node adds nothing")
                .isEqualTo(new HarnessLaunch.Ready(List.of(electron.toString(), script.toString()),
                        "installed adapter " + HarnessLauncher.ADAPTERS_FOLDER));

        variables.put(HarnessLauncher.NODE_ELECTRON, "1");
        assertThat(launcher.resolve(HarnessLauncher.CODEX))
                .isEqualTo(new HarnessLaunch.Ready(List.of(electron.toString(), script.toString()),
                        "installed adapter " + HarnessLauncher.ADAPTERS_FOLDER,
                        Map.of("ELECTRON_RUN_AS_NODE", "1")));

        // An override on the same file (spelt differently) runs as Node too; one on another node does not.
        String sameFile = root.resolve(".").resolve(electron.getFileName()).toString();
        variables.put(HarnessLauncher.CODEX_COMMAND, JSON.writeValueAsString(List.of(sameFile, "mock.cjs")));
        assertThat(launcher.resolve(HarnessLauncher.CODEX)).isInstanceOfSatisfying(HarnessLaunch.Ready.class,
                ready -> assertThat(ready.environment()).containsExactly(Map.entry("ELECTRON_RUN_AS_NODE", "1")));
        variables.put(HarnessLauncher.CODEX_COMMAND, JSON.writeValueAsString(List.of(node.toString(), "mock.cjs")));
        assertThat(launcher.resolve(HarnessLauncher.CODEX)).isInstanceOfSatisfying(HarnessLaunch.Ready.class,
                ready -> assertThat(ready.environment()).isEmpty());
        variables.put(HarnessLauncher.CODEX_COMMAND, "[\"node\", \"mock.cjs\"]");
        assertThat(launcher.resolve(HarnessLauncher.CODEX)).as("a bare name is not compared")
                .isInstanceOfSatisfying(HarnessLaunch.Ready.class, ready -> assertThat(ready.environment()).isEmpty());
    }

    @Test
    void withoutTheAdapterOrNodeCodexIsNotInstalled() throws IOException {
        HarnessLauncher launcher = launcher(Files.createDirectories(root.resolve("repo/runtime")));
        assertThat(launcher.resolve(HarnessLauncher.CODEX)).isInstanceOf(HarnessLaunch.Unavailable.class);
        assertThat(launcher.isAvailable(HarnessLauncher.CODEX)).isFalse();

        // The folder alone (package.json, no npm run adapters:install) is not an install.
        Files.createDirectories(adapters);
        Files.writeString(adapters.resolve("package.json"), "{}", StandardCharsets.UTF_8);
        assertThat(launcher.resolve(HarnessLauncher.CODEX)).isInstanceOfSatisfying(HarnessLaunch.Unavailable.class,
                unavailable -> assertThat(unavailable.reason()).contains("npm run adapters:install"));

        installAdapter();
        variables.put("PATH", root.resolve("elsewhere").toString());
        assertThat(launcher.resolve(HarnessLauncher.CODEX)).isInstanceOfSatisfying(HarnessLaunch.Unavailable.class,
                unavailable -> assertThat(unavailable.reason()).contains("node"));

        variables.put(HarnessLauncher.NODE, "relative/node");
        assertThat(launcher.resolve(HarnessLauncher.CODEX)).isInstanceOf(HarnessLaunch.Unavailable.class);
    }

    @Test
    void anOtherHarnessHasItsOwnOverrideVariable() {
        assertThat(HarnessLauncher.commandOverrideVariable("claude-code"))
                .isEqualTo("AGENTS_ROOM_HARNESS_CLAUDE_CODE_COMMAND");
        assertThat(launcher(root).resolve("claude-code")).isInstanceOf(HarnessLaunch.Unavailable.class);
    }

    private Path installAdapter() throws IOException {
        Files.createDirectories(adapters);
        Files.writeString(adapters.resolve("package.json"), "{}", StandardCharsets.UTF_8);
        Path script = adapters.resolve(HarnessLauncher.ADAPTER_ENTRIES.get(HarnessLauncher.CODEX));
        Files.createDirectories(script.getParent());
        return Files.writeString(script, "", StandardCharsets.UTF_8);
    }

    private HarnessLauncher launcher(Path codeLocation) {
        return new HarnessLauncher(variables::get, codeLocation);
    }
}
