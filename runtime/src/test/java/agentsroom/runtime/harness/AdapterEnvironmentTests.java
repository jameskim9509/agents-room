package agentsroom.runtime.harness;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The adapter environment allowlist (ADR 0004 decision 5, D12). */
class AdapterEnvironmentTests {

    @Test
    void passesOnlyTheAllowlistAndNeverTheShellsVariables() {
        Map<String, String> runtime = new LinkedHashMap<>();
        runtime.put("Path", "C:\\Windows");
        runtime.put("SystemRoot", "C:\\Windows");
        runtime.put("USERPROFILE", "C:\\Users\\me");
        runtime.put("APPDATA", "C:\\Users\\me\\AppData\\Roaming");
        runtime.put("CODEX_HOME", "C:\\codex");
        runtime.put("LC_ALL", "C.UTF-8");
        runtime.put("https_proxy", "http://proxy:3128");
        runtime.put("AGENTS_ROOM_TOKEN", "the-websocket-token-value");
        runtime.put("AGENTS_ROOM_DATA_DIR", "C:\\data");
        runtime.put("NO_BROWSER", "1");
        runtime.put("APP_SERVER_LOGS", "C:\\logs");
        runtime.put("DEFAULT_AUTH_REQUEST", "{}");
        runtime.put("CODEX_PATH", "C:\\other\\codex.exe");
        runtime.put("NODE_OPTIONS", "--require evil.js");
        runtime.put("JAVA_HOME", "C:\\jdk");

        AdapterEnvironment environment = AdapterEnvironment.from(runtime);

        assertThat(environment.variables()).containsOnlyKeys("Path", "SystemRoot", "USERPROFILE", "APPDATA",
                "CODEX_HOME", "LC_ALL", "https_proxy");
        assertThat(environment.variables()).containsEntry("Path", "C:\\Windows");
        assertThat(environment.withheldSecrets()).isEmpty();
    }

    @Test
    void reportsTheNamesOfWithheldCredentialsButNeverTheirValues() {
        AdapterEnvironment environment = AdapterEnvironment.from(Map.of(
                "OPENAI_API_KEY", "value-one", "aws_secret_access_key", "value-two", "PATH", "/usr/bin"));

        assertThat(environment.variables()).containsOnlyKeys("PATH");
        assertThat(environment.withheldSecrets()).containsExactlyInAnyOrder("OPENAI_API_KEY", "AWS_SECRET_ACCESS_KEY");
        assertThat(environment.toString()).doesNotContain("value-one", "value-two", "/usr/bin");
    }

    @Test
    void onlyTheLaunchSetsElectronRunAsNode() {
        Map<String, String> runtime = new LinkedHashMap<>();
        runtime.put("PATH", "/usr/bin");
        runtime.put("electron_run_as_node", "from-the-runtime");
        runtime.put("AGENTS_ROOM_NODE", "C:\\app\\agents-room.exe");
        runtime.put("AGENTS_ROOM_NODE_ELECTRON", "1");

        AdapterEnvironment environment = AdapterEnvironment.from(runtime);
        assertThat(environment.variables()).as("the runtime's own value never passes").containsOnlyKeys("PATH");

        AdapterEnvironment launched = environment.with(HarnessLauncher.RUN_AS_NODE);
        assertThat(launched.variables()).containsOnly(Map.entry("PATH", "/usr/bin"),
                Map.entry("ELECTRON_RUN_AS_NODE", "1"));
        assertThat(environment.with(Map.of())).isSameAs(environment);
        assertThat(AdapterEnvironment.from(Map.of("Path", "/bin")).with(Map.of("PATH", "/other")).variables())
                .as("an added name replaces a passed one whatever its case")
                .containsExactly(Map.entry("PATH", "/other"));
    }

    @Test
    void passesANameOnceWhateverItsCase() {
        Map<String, String> runtime = new LinkedHashMap<>();
        runtime.put("PATH", "first");
        runtime.put("Path", "second");

        assertThat(AdapterEnvironment.from(runtime).variables()).containsExactly(Map.entry("PATH", "first"));
    }
}
