package agentsroom.runtime.logging;

import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The central masking layer for log output (ADR 0004 decision 8). It masks:
 *
 * <ul>
 *   <li>registered secret values wherever they appear (the WebSocket token);
 *   <li>values after secret-looking key names: {@code *_API_KEY}, {@code *TOKEN*}, {@code *SECRET*},
 *       {@code *Authorization} (which covers {@code token=} in URIs and {@code AWS_SESSION_TOKEN});
 *   <li>secret-looking values: {@code Bearer} credentials, {@code AKIA}/{@code ASIA} access key IDs and
 *       {@code sk-} keys.
 * </ul>
 *
 * <p>Over-masking is accepted: a masked word in a log line costs less than a leaked credential.
 *
 * <p>Every pattern runs in time linear in the line length, because log lines can carry untrusted
 * text (adapter stderr, ADR 0004 decision 8) and masking runs inside the log appender.
 */
public final class SecretMasker {

    public static final String MASK = "****";

    /** Shorter values are not registered, so that masking cannot shred ordinary words. */
    static final int MIN_SECRET_LENGTH = 8;

    /** Longer runs are not taken as a key name, which keeps the key search linear. */
    static final int MAX_KEY_NAME_LENGTH = 128;

    private static final Set<String> secrets = new CopyOnWriteArraySet<>();

    /**
     * The key of {@code NAME=value}, {@code NAME: value} or {@code "NAME":"value"}, for any name:
     * group 1 is the name, which {@link #isSecretName} then checks, and the match ends where the value
     * starts. The quantifiers are possessive, so a failed match never backtracks.
     */
    private static final Pattern KEY =
            Pattern.compile("\\b([\\w.-]{1," + MAX_KEY_NAME_LENGTH + "}+)\"?\\s*+[=:]\\s*+\"?");

    /** The value after a secret key, with an optional {@code Bearer }/{@code Basic } scheme. */
    private static final Pattern VALUE =
            Pattern.compile("(?:Bearer\\s+|Basic\\s+)?[^\\s\"'&,;}\\]]+", Pattern.CASE_INSENSITIVE);

    private static final Pattern BEARER = Pattern.compile("(?i)\\b(Bearer)\\s+[A-Za-z0-9._~+/=-]+");
    private static final Pattern AWS_ACCESS_KEY_ID = Pattern.compile("\\b(?:AKIA|ASIA)[A-Z0-9]{16}\\b");
    private static final Pattern SK_KEY = Pattern.compile("\\bsk-[A-Za-z0-9_-]{8,}");

    private SecretMasker() {
    }

    /** Masks {@code secret} in all later log output. Values shorter than 8 characters are ignored. */
    public static void registerSecret(String secret) {
        if (secret != null && secret.length() >= MIN_SECRET_LENGTH) {
            secrets.add(secret);
        }
    }

    /** Returns {@code text} with every secret replaced by {@value #MASK}. */
    public static String mask(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        String masked = text;
        for (String secret : secrets) {
            masked = masked.replace(secret, MASK);
        }
        masked = maskKeyValues(masked);
        masked = BEARER.matcher(masked)
                .replaceAll(match -> Matcher.quoteReplacement(match.group(1) + " " + MASK));
        masked = AWS_ACCESS_KEY_ID.matcher(masked).replaceAll(MASK);
        return SK_KEY.matcher(masked).replaceAll(MASK);
    }

    /** A failed key search costs at most {@value #MAX_KEY_NAME_LENGTH} steps per position. */
    private static String maskKeyValues(String text) {
        Matcher key = KEY.matcher(text);
        Matcher value = VALUE.matcher(text);
        StringBuilder out = null;
        int copied = 0;
        int from = 0;
        while (from < text.length() && key.find(from)) {
            // Without a secret name, keep looking inside the value: "a=token=x" still masks x.
            from = key.end();
            if (!isSecretName(key.group(1)) || !value.region(key.end(), text.length()).lookingAt()) {
                continue;
            }
            if (out == null) {
                out = new StringBuilder(text.length());
            }
            out.append(text, copied, key.end()).append(MASK);
            copied = value.end();
            from = value.end();
        }
        if (out == null) {
            return text;
        }
        return out.append(text, copied, text.length()).toString();
    }

    /**
     * Whether a key name looks like it holds a secret: {@code *_API_KEY}, {@code *TOKEN*},
     * {@code *SECRET*} or {@code *Authorization}, in any case. The masking of structured adapter values
     * uses it for object keys, whose values a text pattern cannot see.
     */
    public static boolean isSecretName(String name) {
        if (name == null) {
            return false;
        }
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.contains("_api_key") || lower.contains("token") || lower.contains("secret")
                || lower.endsWith("authorization");
    }
}
