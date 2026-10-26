package agentsroom.runtime.logging;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/** The stderr masking for output that bypasses logback. Fake values only. */
class MaskingOutputStreamTests {

    /** What {@code println} ends a line with. */
    private static final String NL = System.lineSeparator();

    private final ByteArrayOutputStream sink = new ByteArrayOutputStream();
    private final PrintStream stream =
            new PrintStream(new MaskingOutputStream(sink), true, StandardCharsets.UTF_8);

    @Test
    void masksEachLineBeforePassingItOn() {
        String secret = "stderr-secret-" + "q".repeat(12);
        SecretMasker.registerSecret(secret);

        stream.println("direct stderr " + secret);
        stream.println("token=fake-value and more");

        assertThat(written()).isEqualTo("direct stderr ****" + NL + "token=**** and more" + NL);
    }

    @Test
    void keepsAPartialLineUntilItsNewlineSoASecretIsNeverSplit() {
        stream.print("AGENTS_ROOM_TOKEN=");
        stream.flush();
        stream.print("fake-value");
        stream.flush();

        assertThat(written()).isEmpty();

        stream.println();

        assertThat(written()).isEqualTo("AGENTS_ROOM_TOKEN=****" + NL);
    }

    @Test
    void masksAStackTraceAsTheJvmPrintsIt() {
        new IllegalStateException("failed with token=fake-value").printStackTrace(stream);

        assertThat(written()).startsWith("java.lang.IllegalStateException: failed with token=****" + NL)
                .doesNotContain("fake-value");
    }

    @Test
    void leavesNonAsciiTextAsItWas() {
        stream.println("한국어 로그 ünïcode");

        assertThat(written()).isEqualTo("한국어 로그 ünïcode" + NL);
    }

    @Test
    void passesAnOverlongLineOnInPieces() throws IOException {
        byte[] line = "x".repeat(MaskingOutputStream.MAX_LINE_BYTES + 10).getBytes(StandardCharsets.US_ASCII);

        stream.write(line);

        assertThat(sink.size()).isEqualTo(MaskingOutputStream.MAX_LINE_BYTES);
        stream.close();
        assertThat(sink.size()).isEqualTo(line.length);
    }

    private String written() {
        return sink.toString(StandardCharsets.UTF_8);
    }
}
