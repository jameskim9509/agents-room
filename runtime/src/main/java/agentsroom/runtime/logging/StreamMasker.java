package agentsroom.runtime.logging;

/**
 * Masks one stream of text that arrives in pieces, such as the chunks of one adapter message (ADR 0005
 * decision 11), so that a secret split across pieces is masked like {@link SecretMasker} masks it in one
 * string. Not thread-safe: one stream, one caller.
 *
 * <ul>
 *   <li>A piece is masked together with a bounded context of what came before it
 *       ({@link SecretMasker#maskAfter}): a key name, {@code Bearer} or the start of a secret value in an
 *       earlier piece still masks its continuation in this one, however long the value runs.
 *   <li>Only the start of a secret that is hidden whole and cannot be recognized yet (a registered
 *       secret, an access key ID, an {@code sk-} key), with what a cut may not split from it, is held
 *       back, until a later piece completes or rules it out ({@link SecretMasker#pendingFrom}). That is
 *       at most {@link #holdLimit()} characters; text that cannot start such a secret is never held,
 *       and nothing is ever dropped or reordered.
 *   <li>{@link #flush()} gives up the held start, masked as it stands, when the stream must not wait any
 *       longer (another event follows, or the stream ends). A secret split there shows that start; its
 *       rest is still masked by the context.
 * </ul>
 */
public final class StreamMasker {

    private String context = "";
    private String held = "";

    /**
     * Takes the next piece of the stream.
     *
     * @return the masked text that can be shown now, in stream order; empty when the whole piece is
     *     the held start of a secret
     */
    public String push(String piece) {
        String text = held + piece;
        int ready = SecretMasker.pendingFrom(context, text);
        held = text.substring(ready);
        return show(text.substring(0, ready));
    }

    /** The held start, masked as it stands; empty when nothing is held. The stream can go on afterwards. */
    public String flush() {
        String rest = held;
        held = "";
        return show(rest);
    }

    /** How many characters are held back now. */
    int holding() {
        return held.length();
    }

    /** The most characters a stream holds back: the longest start of a secret that is not recognizable yet. */
    public static int holdLimit() {
        return SecretMasker.holdLimit();
    }

    private String show(String text) {
        if (text.isEmpty()) {
            return text;
        }
        String masked = SecretMasker.maskAfter(context, text);
        context = SecretMasker.seamContext(context + text);
        return masked;
    }
}
