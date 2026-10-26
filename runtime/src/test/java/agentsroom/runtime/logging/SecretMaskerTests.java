package agentsroom.runtime.logging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Fake, low-entropy values only; real-looking ones are built at runtime so secret scanners stay quiet. */
class SecretMaskerTests {

    @Test
    void masksARegisteredSecretAnywhere() {
        String secret = "registered-" + "x".repeat(12);
        SecretMasker.registerSecret(secret);

        assertThat(SecretMasker.mask("connect ws://127.0.0.1:1/ws#" + secret + " now"))
                .isEqualTo("connect ws://127.0.0.1:1/ws#**** now");
    }

    @Test
    void ignoresSecretsTooShortToMaskSafely() {
        SecretMasker.registerSecret("abc");

        assertThat(SecretMasker.mask("abc def")).isEqualTo("abc def");
    }

    @Test
    void masksTheTokenQueryParameter() {
        assertThat(SecretMasker.mask("HTTP GET \"/ws?token=fake-token-value&x=1\""))
                .isEqualTo("HTTP GET \"/ws?token=****&x=1\"");
    }

    @Test
    void masksValuesOfSecretLookingNames() {
        assertThat(SecretMasker.mask("OPENAI_API_KEY=fake")).isEqualTo("OPENAI_API_KEY=****");
        assertThat(SecretMasker.mask("AGENTS_ROOM_TOKEN: fake")).isEqualTo("AGENTS_ROOM_TOKEN: ****");
        assertThat(SecretMasker.mask("{\"AWS_SESSION_TOKEN\":\"fake\"}")).isEqualTo("{\"AWS_SESSION_TOKEN\":\"****\"}");
        assertThat(SecretMasker.mask("aws_secret_access_key = fake")).isEqualTo("aws_secret_access_key = ****");
        assertThat(SecretMasker.mask("authToken=fake")).isEqualTo("authToken=****");
    }

    @Test
    void masksAuthorizationHeadersAndBearerValues() {
        assertThat(SecretMasker.mask("Authorization: Bearer fake.value")).isEqualTo("Authorization: ****");
        assertThat(SecretMasker.mask("sent Bearer fake.value")).isEqualTo("sent Bearer ****");
    }

    @Test
    void masksKeyShapedValues() {
        String accessKeyId = "AKIA" + "Z".repeat(16);
        String skKey = "sk-" + "y".repeat(20);

        assertThat(SecretMasker.mask("id " + accessKeyId + " key " + skKey)).isEqualTo("id **** key ****");
    }

    @Test
    void masksASecretValueThatFollowsAnOrdinaryKey() {
        assertThat(SecretMasker.mask("a=token=fake")).isEqualTo("a=token=****");
        assertThat(SecretMasker.mask("x=1 OPENAI_API_KEY=fake y=2")).isEqualTo("x=1 OPENAI_API_KEY=**** y=2");
    }

    @Test
    void masksPrefixedAuthorizationHeaders() {
        assertThat(SecretMasker.mask("Proxy-Authorization: Basic fake")).isEqualTo("Proxy-Authorization: ****");
    }

    @Test
    void takesLinearTimeOnLongLinesOfKeyCharacters() {
        // The old key pattern backtracked: 32 000 such characters took 12 s.
        List<String> lines = List.of(
                "a.".repeat(32 * 1024),
                "token".repeat(13 * 1024),
                "a=".repeat(32 * 1024),
                "a" + " ".repeat(64 * 1024) + "=",
                "token=" + "x".repeat(64 * 1024),
                "Bearer" + " ".repeat(64 * 1024));

        assertTimeoutPreemptively(Duration.ofSeconds(2), () -> lines.forEach(SecretMasker::mask));
    }

    @Test
    void leavesOrdinaryTextAlone() {
        String line = "Started RuntimeApplication in 3.09 seconds (process running for 4.19)";

        assertThat(SecretMasker.mask(line)).isEqualTo(line);
    }
}
