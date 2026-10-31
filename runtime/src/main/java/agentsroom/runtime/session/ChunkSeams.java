package agentsroom.runtime.session;

import agentsroom.runtime.acp.AgentUpdate.ChunkKind;
import agentsroom.runtime.logging.StreamMasker;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The seams between one agent's text chunks (ADR 0005 decision 11). The adapter's chunk text reaches the
 * session unmasked, and every text stream, one per chunk kind and {@code messageId}, is masked here
 * across its chunks by its own {@link StreamMasker}, so that a secret split across chunks never reaches
 * a renderer in pieces.
 *
 * <ul>
 *   <li>A text chunk is masked with what came before it in its stream. Only the start of a secret that
 *       cannot be recognized yet is held back ({@link StreamMasker#holdLimit()} characters at most), so
 *       a chunk's event can carry less text than the chunk, or none, in which case no event is sent.
 *   <li>A chunk of another {@code messageId} of the same kind ends the previous message of that kind;
 *       chunks of other kinds (a thought between two message chunks) leave a stream as it is.
 *   <li>Before any other event of the agent, every held start goes out, masked as it stands, in its own
 *       chunk event: held text never passes an event that came after it. Run and session boundaries
 *       also forget the streams.
 * </ul>
 *
 * <p>Not thread-safe: used on the agent's mailbox, inside its publish step.
 */
final class ChunkSeams {

    /** Masked text of one stream to send as a chunk event. */
    record Text(ChunkKind kind, String messageId, String text, long receivedAt) {
    }

    private record Key(ChunkKind kind, String messageId) {
    }

    private static final class Stream {

        final StreamMasker masker = new StreamMasker();
        long receivedAt;
    }

    private final Map<Key, Stream> streams = new LinkedHashMap<>();

    /**
     * A text chunk of one stream.
     *
     * @return what to send, in order: the held starts of the messages of this kind it ends, then this
     *     chunk's text that can be shown now; each only when it is not empty
     */
    List<Text> accept(ChunkKind kind, String messageId, String text, long receivedAt) {
        List<Text> ready = new ArrayList<>();
        Iterator<Map.Entry<Key, Stream>> others = streams.entrySet().iterator();
        while (others.hasNext()) {
            Map.Entry<Key, Stream> other = others.next();
            Key key = other.getKey();
            if (key.kind() == kind && !Objects.equals(key.messageId(), messageId)) {
                add(ready, key, other.getValue().receivedAt, other.getValue().masker.flush());
                others.remove();
            }
        }
        Key key = new Key(kind, messageId);
        Stream stream = streams.computeIfAbsent(key, ignored -> new Stream());
        stream.receivedAt = receivedAt;
        add(ready, key, receivedAt, stream.masker.push(text));
        return ready;
    }

    /**
     * Before another event: every held start, masked, in the order the streams began.
     *
     * @param end whether the event ends the streams (a run or session boundary); they are forgotten then
     */
    List<Text> flush(boolean end) {
        List<Text> ready = new ArrayList<>();
        streams.forEach((key, stream) -> add(ready, key, stream.receivedAt, stream.masker.flush()));
        if (end) {
            streams.clear();
        }
        return ready;
    }

    private static void add(List<Text> ready, Key key, long receivedAt, String text) {
        if (!text.isEmpty()) {
            ready.add(new Text(key.kind(), key.messageId(), text, receivedAt));
        }
    }
}
