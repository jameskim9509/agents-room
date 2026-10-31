package agentsroom.runtime.logging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Text in pieces (ADR 0005 decision 11): a secret split across two or three pieces, at every position,
 * never shows in any piece, ordinary text comes out unchanged and whole, and nothing is held back longer
 * than the bound. Fake, low-entropy values only, built at run time so secret scanners stay quiet.
 */
class StreamMaskerTests {

    private static final String SK_KEY = String.join("-", "sk", "mockmockmockmock1234");
    private static final String ACCESS_KEY_ID = "AKIA" + "QZ".repeat(8);
    private static final String REGISTERED = "stream-registered-" + "q".repeat(20);

    /** Texts with one secret each, and the secret value: none of its 4-character windows may ever show. */
    private static final List<Case> SECRETS = List.of(
            new Case("and the key " + SK_KEY + " stays hidden.", SK_KEY),
            new Case("id " + ACCESS_KEY_ID + " is masked", ACCESS_KEY_ID),
            new Case("header Authorization: Bearer Zq7.Wx9-Vb3 sent", "Zq7.Wx9-Vb3"),
            new Case("sent Bearer Zq7.Wx9~Vb3/Kp5= over", "Zq7.Wx9~Vb3/Kp5="),
            new Case("env OPENAI_API_KEY=Zq7Wx9Vb3Kp5 set", "Zq7Wx9Vb3Kp5"),
            new Case("GET /ws?token=Zq7-Wx9-Vb3-Kp5&x=1", "Zq7-Wx9-Vb3-Kp5"),
            new Case("{\"api_token\":\"Zq7Wx9Vb3Kp5\"}", "Zq7Wx9Vb3Kp5"),
            new Case("aws_secret_access_key = Zq7Wx9Vb3Kp5 done", "Zq7Wx9Vb3Kp5"),
            new Case("Proxy-Authorization: Basic Zq7Wx9Vb3Kp5 end", "Zq7Wx9Vb3Kp5"),
            new Case("OPENAI_API_KEY=" + SK_KEY + " and more", SK_KEY.substring(3)),
            new Case("a=token=Zq7Wx9Vb3Kp5 b", "Zq7Wx9Vb3Kp5"),
            // Followed by a letter the ID is no ID, until the registered secret completes and is masked first.
            new Case("id " + ACCESS_KEY_ID + REGISTERED + " end", ACCESS_KEY_ID));

    /** Ordinary text, some of it ending in what could start a secret ("s", "A", "sk", "Bear"). */
    private static final List<String> ORDINARY = List.of(
            "The README is read, git status is clean.",
            "update 1",
            "Plan A or B: it's s, sk and ski, a polar Bear, AKIA alone, tokens of thanks.",
            "runtime: node 24.22.0, electron 45.0.0",
            "cwd: C:\\Users\\me\\project-1 | process cwd: C:\\Users\\me\\project-1",
            "Running the tests... token count 1200 and a=b, x: y",
            // As long as an access key ID, but one character longer: not one.
            "build AKIA" + "QZ".repeat(8) + "X is a 21-character word");

    @BeforeAll
    static void registerASecret() {
        SecretMasker.registerSecret(REGISTERED);
    }

    @Test
    void aSecretSplitAtAnyPositionIntoTwoOrThreePiecesNeverShows() {
        for (Case secret : SECRETS) {
            String whole = SecretMasker.mask(secret.text());
            assertThat(windows(secret.secret())).as(secret.text()).noneMatch(whole::contains);
            for (List<String> pieces : splits(secret.text())) {
                List<String> shown = stream(pieces);
                assertThat(String.join("", shown)).as("%s", pieces).isEqualTo(whole);
                for (String piece : shown) {
                    assertThat(windows(secret.secret())).as("%s in %s", piece, pieces).noneMatch(piece::contains);
                }
            }
        }
    }

    @Test
    void aSecretArrivingCharacterByCharacterIsMaskedLikeTheWholeText() {
        for (Case secret : SECRETS) {
            List<String> pieces = secret.text().chars().mapToObj(c -> String.valueOf((char) c)).toList();
            assertThat(String.join("", stream(pieces))).isEqualTo(SecretMasker.mask(secret.text()));
        }
    }

    @Test
    void aRegisteredSecretShowsAtMostItsFirstThreeCharacters() {
        String text = "connect ws#" + REGISTERED + " now";
        for (List<String> pieces : splits(text)) {
            List<String> shown = stream(pieces);
            String joined = String.join("", shown);
            assertThat(joined).as("%s", pieces).startsWith("connect ws#").endsWith("**** now");
            assertThat(joined.length() - "connect ws#**** now".length())
                    .as("a start shorter than %d is not held back", SecretMasker.REGISTERED_START_HOLD)
                    .isLessThan(SecretMasker.REGISTERED_START_HOLD);
            for (String piece : shown) {
                assertThat(windows(REGISTERED)).as("%s in %s", piece, pieces).noneMatch(piece::contains);
            }
        }
    }

    @Test
    void anOpenEndedSecretOfAnyLengthStaysMaskedAcrossPieces() {
        String value = "Q".repeat(3 * SecretMasker.SEAM_CONTEXT);
        List<String> texts = List.of(
                "x sk-" + value + " rest",
                "Authorization: Bearer " + value + " end",
                "sent Bearer " + value + " end",
                "url?token=" + value + "&next=1",
                "my_token = \"" + value + "\" done");
        for (String text : texts) {
            String whole = SecretMasker.mask(text);
            assertThat(whole).doesNotContain("QQQQ");
            for (int at = 1; at < text.length(); at++) {
                assertThat(String.join("", stream(List.of(text.substring(0, at), text.substring(at)))))
                        .as("%s split at %d", text.substring(0, 20), at).isEqualTo(whole);
            }
            for (int first = 7; first < text.length(); first += 97) {
                for (int second = first + 1; second < text.length(); second += 89) {
                    List<String> pieces = List.of(text.substring(0, first), text.substring(first, second),
                            text.substring(second));
                    List<String> shown = stream(pieces);
                    assertThat(String.join("", shown)).isEqualTo(whole);
                    assertThat(shown).noneMatch(piece -> piece.contains("QQQQ"));
                }
            }
            // A value far longer than the context, in many small pieces.
            List<String> small = new ArrayList<>();
            for (int at = 0; at < text.length(); at += 7) {
                small.add(text.substring(at, Math.min(text.length(), at + 7)));
            }
            assertThat(String.join("", stream(small))).isEqualTo(whole);
        }
    }

    /**
     * However much whitespace (spaces, newlines) separates a key, its separator or a scheme from the value,
     * and wherever the pieces split it, the value never shows: the kept context cuts long whitespace runs,
     * which the patterns read alike, so the key stays in it. The text comes out as the whole text masks,
     * except that a scheme's spaces beyond what is held back already showed (the whole text reads
     * {@code "Bearer ****"}).
     */
    @Test
    void aLongRunOfWhitespaceBeforeTheValueNeverLetsTheValueShow() {
        String value = "Zq7Wx9Vb3Kp5";
        for (String gap : List.of(" ".repeat(600), "\n".repeat(600), " \r\n\t".repeat(150), " ".repeat(513))) {
            List<String> texts = List.of(
                    "Bearer" + gap + value + " end",
                    "Authorization:" + gap + "Bearer" + gap + value + " end",
                    "token=" + gap + value + " end",
                    "token" + gap + "=" + value + " end",
                    "token" + gap + "=" + gap + value + " end",
                    "{\"api_token\":" + gap + "\"" + value + "\"}",
                    "{\"api_token\"" + gap + ":" + gap + "\"" + value + "\"}",
                    "x".repeat(700) + " OPENAI_API_KEY" + gap + "=" + gap + value + " end");
            for (String text : texts) {
                String whole = SecretMasker.mask(text);
                assertThat(windows(value)).as("whole: %s", whole.strip()).noneMatch(whole::contains);
                for (int at = 1; at < text.length(); at++) {
                    assertValueHidden(text, whole, value, List.of(text.substring(0, at), text.substring(at)));
                }
                int valueAt = text.indexOf(value);
                for (int first = 1; first < valueAt; first += 37) {
                    assertValueHidden(text, whole, value,
                            List.of(text.substring(0, first), text.substring(first, valueAt), text.substring(valueAt)));
                }
                List<String> small = new ArrayList<>();
                for (int at = 0; at < text.length(); at += 7) {
                    small.add(text.substring(at, Math.min(text.length(), at + 7)));
                }
                assertValueHidden(text, whole, value, small);
            }
        }
    }

    private static void assertValueHidden(String text, String whole, String value, List<String> pieces) {
        List<String> shown = stream(pieces);
        for (String piece : shown) {
            assertThat(windows(value)).as("%s split %s", text.strip(), pieces.size()).noneMatch(piece::contains);
        }
        assertThat(String.join("", shown).replaceAll("\\s+", " ")).as("%s", text.strip())
                .isEqualTo(whole.replaceAll("\\s+", " "));
    }

    @Test
    void ordinaryTextComesOutUnchangedWholeAndInOrder() {
        for (String text : ORDINARY) {
            assertThat(SecretMasker.mask(text)).as("the corpus is ordinary text").isEqualTo(text);
            for (List<String> pieces : splits(text)) {
                StreamMasker stream = new StreamMasker();
                StringBuilder shown = new StringBuilder();
                StringBuilder sent = new StringBuilder();
                for (String piece : pieces) {
                    shown.append(stream.push(piece));
                    sent.append(piece);
                    // Nothing is lost or reordered: what is shown plus what is held is what was sent.
                    assertThat(shown.length() + stream.holding()).isEqualTo(sent.length());
                    assertThat(sent.toString()).startsWith(shown.toString());
                }
                shown.append(stream.flush());
                assertThat(shown.toString()).as("%s", pieces).isEqualTo(text);
                assertThat(stream.holding()).isZero();
            }
        }
    }

    /**
     * Random texts of words that make and break secrets of every kind (key names and their separators,
     * schemes, access key IDs, {@code sk-} keys, delimiters), in random pieces of 1 to 30 characters:
     * the pieces shown join to the whole text masked at once, so nothing is lost, reordered or left
     * unmasked at a seam, and the hold-back stays within its bound.
     */
    @Test
    void randomTextsInRandomPiecesJoinToTheWholeTextMasked() {
        String[] words = {"token", "=", ":", "\"", "'", "&", ",", ";", "}", "]", " ", "  ", "\n", "\t", "abc", "1234",
            "X", "a", "s", "k", "-", "_", ".", "/", "+", "~", "Bearer", "bearer", "Basic", "Basic  ", "Authorization",
            "OPENAI_API_KEY", "my_secret", "sk-", "sk-abc", SK_KEY, "mockmock", "AKIA", "ASIA", "-AKIA", "QZQZ",
            "QZQZQZQZQZQZQZQZ", ACCESS_KEY_ID};
        Random random = new Random(20261027);
        for (int round = 0; round < 20_000; round++) {
            StringBuilder text = new StringBuilder();
            for (int count = 1 + random.nextInt(25); count > 0; count--) {
                text.append(words[random.nextInt(words.length)]);
            }
            String whole = text.toString();
            List<String> pieces = new ArrayList<>();
            for (int at = 0; at < whole.length();) {
                int length = 1 + random.nextInt(Math.min(30, whole.length() - at));
                pieces.add(whole.substring(at, at + length));
                at += length;
            }
            StreamMasker stream = new StreamMasker();
            StringBuilder shown = new StringBuilder();
            for (String piece : pieces) {
                shown.append(stream.push(piece));
                assertThat(stream.holding()).as("%s", pieces).isLessThanOrEqualTo(StreamMasker.holdLimit());
            }
            shown.append(stream.flush());
            assertThat(shown.toString()).as("%s", pieces).isEqualTo(SecretMasker.mask(whole));
        }
    }

    @Test
    void textWithoutTheStartOfASecretIsNeverHeldBack() {
        StreamMasker stream = new StreamMasker();

        assertThat(stream.push("The README is read, ")).isEqualTo("The README is read, ");
        assertThat(stream.push("git status is clean")).isEqualTo("git status is clean");
        assertThat(stream.push("update 300")).isEqualTo("update 300");
        assertThat(stream.push(" and a token")).as("a key name shows anyway").isEqualTo(" and a token");
        assertThat(stream.push("=xyz")).as("its value, in the next piece, is masked").isEqualTo("=****");
    }

    @Test
    void theHoldBackNeverExceedsTheBound() {
        int limit = StreamMasker.holdLimit();
        // Other tests in this JVM register their own secrets (the WebSocket tokens, 43 characters).
        assertThat(limit).isGreaterThanOrEqualTo(2 * REGISTERED.length() + SecretMasker.LONGEST_OPEN_START)
                .isLessThan(128);
        List<String> texts = new ArrayList<>(ORDINARY);
        SECRETS.forEach(secret -> texts.add(secret.text()));
        texts.add("AKIA AKIAQZQZ ASIA sk- sk-sk-sk-ab s sk sk-a A AK");
        texts.add("A".repeat(300) + " AKIA" + "Z".repeat(15));
        texts.add(REGISTERED.substring(0, REGISTERED.length() - 1));
        for (String text : texts) {
            for (List<String> pieces : splits(text)) {
                StreamMasker stream = new StreamMasker();
                for (String piece : pieces) {
                    stream.push(piece);
                    assertThat(stream.holding()).as("%s", pieces).isLessThanOrEqualTo(limit);
                }
            }
        }
    }

    @Test
    void aFlushGivesUpTheHeldStartAndTheRestIsStillMasked() {
        StreamMasker stream = new StreamMasker();

        assertThat(stream.push("key sk-moc")).isEqualTo("key ");
        assertThat(stream.flush()).as("another event came: the start shows as it stands").isEqualTo("sk-moc");
        assertThat(stream.push("kmockmockmock1234 end")).as("the rest is masked by the context")
                .isEqualTo("**** end").doesNotContain("mock");
    }

    @Test
    void masksLongPiecesInLinearTime() {
        List<String> pieces = List.of("token=" + "x".repeat(64 * 1024), "a.".repeat(32 * 1024),
                "sk-" + "y".repeat(64 * 1024), "Bearer" + " ".repeat(64 * 1024) + "z");

        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
            StreamMasker stream = new StreamMasker();
            pieces.forEach(stream::push);
            stream.flush();
        });
    }

    private static List<String> stream(List<String> pieces) {
        StreamMasker stream = new StreamMasker();
        List<String> shown = new ArrayList<>();
        for (String piece : pieces) {
            shown.add(stream.push(piece));
        }
        shown.add(stream.flush());
        return shown;
    }

    /** Every split into two pieces and every split into three non-empty pieces. */
    private static List<List<String>> splits(String text) {
        List<List<String>> splits = new ArrayList<>();
        for (int first = 1; first < text.length(); first++) {
            splits.add(List.of(text.substring(0, first), text.substring(first)));
            for (int second = first + 1; second < text.length(); second++) {
                splits.add(List.of(text.substring(0, first), text.substring(first, second), text.substring(second)));
            }
        }
        return splits;
    }

    /** Every 4-character piece of a secret value. */
    private static List<String> windows(String secret) {
        List<String> windows = new ArrayList<>();
        for (int at = 0; at + 4 <= secret.length(); at++) {
            windows.add(secret.substring(at, at + 4));
        }
        return windows;
    }

    private record Case(String text, String secret) {
    }
}
