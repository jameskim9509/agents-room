package agentsroom.runtime.logging;

import java.util.Arrays;
import java.util.List;
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
 *
 * <p>Text that arrives in pieces (an adapter's message chunks) is masked across the seams by
 * {@link StreamMasker}, with the knowledge below: what a piece may still turn into
 * ({@link #pendingFrom}), how a piece reads after what came before it ({@link #maskAfter}) and how much
 * of what came before to keep ({@link #seamContext}).
 */
public final class SecretMasker {

    public static final String MASK = "****";

    /** Shorter values are not registered, so that masking cannot shred ordinary words. */
    static final int MIN_SECRET_LENGTH = 8;

    /** Longer runs are not taken as a key name, which keeps the key search linear. */
    static final int MAX_KEY_NAME_LENGTH = 128;

    /**
     * The longest end of a piece that the next piece can still turn into an access key ID or an {@code
     * sk-} key, or out of one: an ID is 20 characters ({@code AKIA} and 16 more) and is one only when no
     * letter or digit follows, an {@code sk-} key matches from 11. A piece of text that ends in such a
     * start is held back until the next piece tells.
     */
    static final int LONGEST_OPEN_START = 20;

    /** An access key ID: {@code AKIA} or {@code ASIA} and 16 more. */
    private static final int AWS_ACCESS_KEY_ID_LENGTH = 20;

    /** The shortest {@code sk-} key: {@code sk-} and 8 more. */
    static final int SK_KEY_MIN_LENGTH = 11;

    /**
     * A registered secret's start is held back from this many characters on. Shorter starts end ordinary
     * text by chance (one random character ends a piece once in 64), so they are not held: at most this
     * many characters less one of a registered secret can show before the rest is masked.
     */
    static final int REGISTERED_START_HOLD = 4;

    /** How much text before a piece the masking of that piece sees (more than a key name and its separators). */
    static final int SEAM_CONTEXT = 512;

    /**
     * The start kept of a secret value that runs longer than {@link #SEAM_CONTEXT}: its key name,
     * separators and scheme, so that the rest of a value of any length is still recognized.
     */
    static final int SEAM_HEAD = 256;

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

    /** The schemes a masked value may start with: {@link #VALUE}'s and {@link #BEARER}'s. */
    private static final Pattern SCHEME = Pattern.compile("(?i)\\b(?:Bearer|Basic)");
    private static final int SCHEME_WORD_LENGTH = "Bearer".length();
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

    // ── Text in pieces (StreamMasker) ─────────────────────────────────

    /**
     * How many characters of {@code text}, which follows {@code context}, can be masked and shown now.
     * The rest is held back until the next piece tells what it is, because shown now it could stay
     * visible:
     *
     * <ul>
     *   <li>the start of a secret this masker hides whole (a registered secret, an access key ID, an
     *       {@code sk-} key) that the next piece may complete, or an access key ID it may undo;
     *   <li>so that no cut splits such a secret where the part before the cut would not be masked on its
     *       own: the cut moves to the secret's start;
     *   <li>the spaces after a {@code Bearer} or {@code Basic} scheme, which become part of the masked
     *       value once it comes (otherwise the text would read {@code "Bearer  ****"} instead of
     *       {@code "Bearer ****"}).
     * </ul>
     *
     * <p>The rest is never longer than {@link #holdLimit()}. Everything else needs no holding back: a key
     * name and its separators, or {@code Bearer}, are shown anyway, and a secret value that has started
     * is masked as far as it goes, its continuation by {@link #maskAfter} with the context.
     */
    static int pendingFrom(String context, String text) {
        String all = context + text;
        int offset = context.length();
        int pending = all.length();
        for (String secret : secrets) {
            // A start that began in the context (shown while it was shorter) is held from here on.
            for (int length = Math.min(secret.length() - 1, all.length()); length >= REGISTERED_START_HOLD; length--) {
                if (all.endsWith(secret.substring(0, length))) {
                    pending = Math.min(pending, Math.max(offset, all.length() - length));
                    break;
                }
            }
        }
        // The patterns run on the text as mask() shows it to them: registered secrets already masked.
        String view = withRegisteredHidden(all);
        for (Pattern pattern : List.of(AWS_ACCESS_KEY_ID, SK_KEY)) {
            Matcher attempt = pattern.matcher(view).useTransparentBounds(true).useAnchoringBounds(false);
            for (int at = Math.max(offset, all.length() - LONGEST_OPEN_START); at < pending; at++) {
                attempt.region(at, all.length());
                // Not yet a match but the next piece may make one, or a match the next piece may undo
                // (an ID that one more letter makes an ordinary word).
                if (attempt.lookingAt() ? attempt.requireEnd() : attempt.hitEnd()) {
                    pending = at;
                    break;
                }
            }
        }
        int floor = Math.max(offset, all.length() - holdLimit());
        for (int before = -1; before != pending;) {
            before = pending;
            pending = Math.max(floor, outsideWholeSecrets(all, view, offset, pending));
            pending = Math.max(floor, beforeSchemeSpaces(view, pending));
        }
        return pending - offset;
    }

    /**
     * {@code cut}, or the start of a secret masked whole that runs across it while its part before the
     * cut would not be masked on its own: a registered secret, an access key ID, or an {@code sk-} key
     * with fewer than {@value #SK_KEY_MIN_LENGTH} characters before the cut. Only a secret that starts at
     * {@code from} or later counts.
     */
    private static int outsideWholeSecrets(String all, String view, int from, int cut) {
        int start = cut;
        for (String secret : secrets) {
            int at = all.indexOf(secret, Math.max(from, cut - secret.length() + 1));
            if (at >= 0 && at < cut) {
                start = Math.min(start, at);
            }
        }
        for (Pattern pattern : List.of(AWS_ACCESS_KEY_ID, SK_KEY)) {
            int shortest = pattern == SK_KEY ? SK_KEY_MIN_LENGTH : AWS_ACCESS_KEY_ID_LENGTH;
            Matcher match = pattern.matcher(view).useTransparentBounds(true).useAnchoringBounds(false);
            match.region(Math.max(from, cut - shortest + 1), view.length());
            while (match.find() && match.start() < cut) {
                if (match.end() > cut) {
                    start = Math.min(start, match.start());
                    break;
                }
            }
        }
        // An access key ID the cut would end: shown alone it is masked, while the text goes on with a letter
        // or digit and is no ID.
        int id = cut - AWS_ACCESS_KEY_ID_LENGTH;
        if (id >= from && cut < view.length() && isWordCharacter(view.charAt(cut))) {
            Matcher ending = AWS_ACCESS_KEY_ID.matcher(view.substring(0, cut)).useTransparentBounds(true)
                    .useAnchoringBounds(false);
            if (ending.region(id, cut).lookingAt()) {
                start = Math.min(start, id);
            }
        }
        return start;
    }

    private static boolean isWordCharacter(char c) {
        return c == '_' || (c < 128 && Character.isLetterOrDigit(c));
    }

    /**
     * {@code text} with every registered secret replaced by as many {@code *}: what the patterns after
     * the registered secrets see in {@link #mask} (a masked secret ends a word), at the same positions.
     */
    private static String withRegisteredHidden(String text) {
        char[] hidden = null;
        for (String secret : secrets) {
            for (int at = text.indexOf(secret); at >= 0; at = text.indexOf(secret, at + secret.length())) {
                if (hidden == null) {
                    hidden = text.toCharArray();
                }
                Arrays.fill(hidden, at, at + secret.length(), '*');
            }
        }
        return hidden == null ? text : new String(hidden);
    }

    /**
     * Where the spaces after a {@code Bearer} or {@code Basic} scheme right before {@code cut} start, or
     * {@code cut}: they wait for the value they lead to.
     */
    private static int beforeSchemeSpaces(String all, int cut) {
        int spaces = cut;
        while (spaces > 0 && Character.isWhitespace(all.charAt(spaces - 1))) {
            spaces--;
        }
        if (spaces == cut || cut - spaces > LONGEST_OPEN_START) {
            return cut;
        }
        Matcher scheme = SCHEME.matcher(all).useTransparentBounds(true).useAnchoringBounds(false);
        return scheme.region(Math.max(0, spaces - SCHEME_WORD_LENGTH), spaces).find() && scheme.end() == spaces
                ? spaces : cut;
    }

    /**
     * The most characters {@link #pendingFrom} holds back: twice the longest secret it holds whole (the
     * start of one that a cut may not split, then one that the next piece may complete) and a scheme's
     * spaces. With the WebSocket token registered (43 characters) that is 106; usually far less is held.
     */
    static int holdLimit() {
        int longest = LONGEST_OPEN_START;
        for (String secret : secrets) {
            longest = Math.max(longest, secret.length());
        }
        return 2 * longest + LONGEST_OPEN_START;
    }

    /**
     * {@code text} masked as it reads after {@code context}: {@code mask(context + text)} without what
     * {@code context} alone masks to. So a key name, a {@code Bearer} or a secret value that started in
     * the context still masks what follows it in {@code text}. Every character returned comes from
     * {@code mask(context + text)}; when a match that starts in the context changes how the context
     * itself masks (the rest of a secret whose start was already shown), the result starts where the two
     * differ.
     */
    static String maskAfter(String context, String text) {
        if (text.isEmpty()) {
            return text;
        }
        if (context.isEmpty()) {
            return mask(text);
        }
        String all = mask(context + text);
        String head = mask(context);
        if (all.startsWith(head)) {
            return all.substring(head.length());
        }
        int same = 0;
        int limit = Math.min(all.length(), head.length());
        while (same < limit && all.charAt(same) == head.charAt(same)) {
            same++;
        }
        return all.substring(same);
    }

    /**
     * The context to keep after {@code text} was shown, at most {@link #SEAM_CONTEXT} characters: the
     * end of {@code text}, or, when it ends inside a secret value that started longer ago (a key's
     * value, a {@code Bearer} value, an {@code sk-} key), that secret's first {@link #SEAM_HEAD}
     * characters and the end of {@code text}. The middle of such a value is more of the same characters,
     * so the shortened context masks what follows exactly as the whole would.
     */
    static String seamContext(String text) {
        if (text.length() <= SEAM_CONTEXT) {
            return text;
        }
        int open = openSecretStart(text);
        if (open >= 0 && text.length() - open > SEAM_CONTEXT) {
            return text.substring(open, open + SEAM_HEAD) + text.substring(text.length() - (SEAM_CONTEXT - SEAM_HEAD));
        }
        return text.substring(text.length() - SEAM_CONTEXT);
    }

    /** Where the earliest secret value that runs to the end of {@code text} starts, or -1. */
    private static int openSecretStart(String text) {
        int open = -1;
        Matcher key = KEY.matcher(text);
        Matcher value = VALUE.matcher(text);
        int from = 0;
        while (from < text.length() && key.find(from)) {
            from = key.end();
            if (isSecretName(key.group(1)) && value.region(key.end(), text.length()).lookingAt()) {
                if (value.end() == text.length()) {
                    open = key.start();
                    break;
                }
                from = value.end();
            }
        }
        for (Pattern pattern : List.of(BEARER, SK_KEY)) {
            Matcher match = pattern.matcher(text);
            while (match.find()) {
                if (match.end() == text.length() && (open < 0 || match.start() < open)) {
                    open = match.start();
                }
            }
        }
        return open;
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
