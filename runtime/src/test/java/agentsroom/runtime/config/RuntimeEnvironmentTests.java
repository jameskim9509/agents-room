package agentsroom.runtime.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RuntimeEnvironmentTests {

    private static final String TOKEN = "environment-test-token-xxxxxxxxxxxxxxxxxxxx";

    @TempDir
    Path dataDir;

    @Test
    void readsTheThreeVariables() {
        RuntimeEnvironment environment = RuntimeEnvironment.from(variables(Map.of(
                RuntimeEnvironment.TOKEN, TOKEN,
                RuntimeEnvironment.DATA_DIR, dataDir.toString(),
                RuntimeEnvironment.ALLOWED_ORIGINS, "app://bundle, http://localhost:5173,")));

        assertThat(environment.token()).isEqualTo(TOKEN);
        assertThat(environment.dataDirectory()).isEqualTo(dataDir);
        assertThat(environment.runtimeDirectory()).isEqualTo(dataDir.resolve("runtime"));
        assertThat(environment.allowedOrigins()).containsExactlyInAnyOrder("app://bundle", "http://localhost:5173");
    }

    @Test
    void allowsOnlyThePackagedOriginWhenOriginsAreNotSet() {
        RuntimeEnvironment environment = RuntimeEnvironment.from(variables(Map.of(
                RuntimeEnvironment.TOKEN, TOKEN, RuntimeEnvironment.DATA_DIR, dataDir.toString())));

        assertThat(environment.allowedOrigins()).containsExactly(RuntimeEnvironment.DEFAULT_ALLOWED_ORIGIN);
    }

    @Test
    void anEmptyOriginListAllowsNoBrowserOrigin() {
        RuntimeEnvironment environment = RuntimeEnvironment.from(variables(Map.of(
                RuntimeEnvironment.TOKEN, TOKEN,
                RuntimeEnvironment.DATA_DIR, dataDir.toString(),
                RuntimeEnvironment.ALLOWED_ORIGINS, "")));

        assertThat(environment.allowedOrigins()).isEmpty();
    }

    @Test
    void failsFastWithoutToken() {
        assertThatThrownBy(() -> RuntimeEnvironment.from(variables(Map.of(
                        RuntimeEnvironment.DATA_DIR, dataDir.toString()))))
                .isInstanceOf(RuntimeEnvironmentException.class)
                .hasMessageContaining(RuntimeEnvironment.TOKEN);
        assertThatThrownBy(() -> RuntimeEnvironment.from(variables(Map.of(
                        RuntimeEnvironment.TOKEN, "  ", RuntimeEnvironment.DATA_DIR, dataDir.toString()))))
                .isInstanceOf(RuntimeEnvironmentException.class)
                .hasMessageContaining(RuntimeEnvironment.TOKEN);
    }

    @Test
    void failsFastOnATokenThatIsNot32BytesOfBase64Url() {
        String tooShort = TOKEN.substring(1);
        String tooLong = TOKEN + "x";
        String padded = TOKEN.substring(1) + "=";
        String plainBase64 = "+" + TOKEN.substring(1);
        for (String token : new String[] {tooShort, tooLong, padded, plainBase64}) {
            assertThatThrownBy(() -> RuntimeEnvironment.from(variables(Map.of(
                            RuntimeEnvironment.TOKEN, token, RuntimeEnvironment.DATA_DIR, dataDir.toString()))))
                    .as("token %s", token)
                    .isInstanceOf(RuntimeEnvironmentException.class)
                    .hasMessageContaining(RuntimeEnvironment.TOKEN)
                    .hasMessageContaining("base64url")
                    .message().doesNotContain(token);
        }
        assertThatThrownBy(() -> RuntimeEnvironment.from(variables(Map.of(
                        RuntimeEnvironment.TOKEN, "a", RuntimeEnvironment.DATA_DIR, dataDir.toString()))))
                .isInstanceOf(RuntimeEnvironmentException.class)
                .hasMessageContaining("base64url");
    }

    @Test
    void stripsSurroundingWhitespaceFromTheToken() {
        RuntimeEnvironment environment = RuntimeEnvironment.from(variables(Map.of(
                RuntimeEnvironment.TOKEN, " " + TOKEN + "\n", RuntimeEnvironment.DATA_DIR, dataDir.toString())));

        assertThat(environment.token()).isEqualTo(TOKEN);
    }

    @Test
    void failsFastWithoutAnAbsoluteDataDirectory() {
        assertThatThrownBy(() -> RuntimeEnvironment.from(variables(Map.of(RuntimeEnvironment.TOKEN, TOKEN))))
                .isInstanceOf(RuntimeEnvironmentException.class)
                .hasMessageContaining(RuntimeEnvironment.DATA_DIR);
        assertThatThrownBy(() -> RuntimeEnvironment.from(variables(Map.of(
                        RuntimeEnvironment.TOKEN, TOKEN, RuntimeEnvironment.DATA_DIR, "relative/dir"))))
                .isInstanceOf(RuntimeEnvironmentException.class)
                .hasMessageContaining("absolute");
    }

    @Test
    void toStringNeverShowsTheToken() {
        RuntimeEnvironment environment = RuntimeEnvironment.from(variables(Map.of(
                RuntimeEnvironment.TOKEN, TOKEN, RuntimeEnvironment.DATA_DIR, dataDir.toString())));

        assertThat(environment.toString()).doesNotContain(TOKEN).contains("****");
    }

    private static Function<String, String> variables(Map<String, String> values) {
        Map<String, String> copy = new HashMap<>(values);
        return copy::get;
    }
}
