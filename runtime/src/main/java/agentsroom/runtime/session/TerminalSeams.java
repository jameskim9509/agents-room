package agentsroom.runtime.session;

import agentsroom.contract.ToolCallState;
import agentsroom.runtime.logging.StreamMasker;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The seams between the pieces of one agent's command output (ADR 0005 decision 11). codex-acp sends a
 * command's output in pieces, one per tool call update ({@code _meta.terminal_output.data}, because the
 * runtime declares {@code terminal_output}). The pieces reach the session unmasked, and the output of each
 * terminal (its tool call and {@code terminal_id}) is masked here across its pieces by its own
 * {@link StreamMasker}, so that a secret split across pieces never reaches a renderer in parts.
 *
 * <ul>
 *   <li>A piece is masked with the output before it. Only the start of a secret that cannot be recognized
 *       yet is held back ({@link StreamMasker#holdLimit()} characters at most), so the update's event can
 *       carry less output than the piece, or none.
 *   <li>Pieces of other terminals, and text chunks, leave a terminal's output as it is.
 *   <li>Before any other event of the agent, every held start goes out, masked as it stands, in an update
 *       event of its own: the tool call as the terminal's last piece left it, with only that output in its
 *       {@code vendorMeta}. A piece that finishes its tool call carries its terminal's held rest itself.
 *       Run and session boundaries, and a finished tool call, forget the terminals.
 * </ul>
 *
 * <p>Not thread-safe: used on the agent's mailbox, inside its publish step.
 */
final class TerminalSeams {

    /** One terminal: the tool call and the adapter's {@code terminal_id}. */
    record Terminal(String toolCallId, String terminalId) {
    }

    /** Held output of one terminal, as the tool call state of the update event that carries it. */
    record Held(ToolCallState state, long receivedAt) {
    }

    private static final class Stream {

        final StreamMasker masker = new StreamMasker();
        /** The tool call as the terminal's last piece reported it, without the output. */
        ToolCallState last;
        long receivedAt;
    }

    private final Map<Terminal, Stream> streams = new LinkedHashMap<>();

    /**
     * A piece of a terminal's output.
     *
     * @param state the tool call this piece's update leaves, without the output; a later flush repeats it
     * @return the masked output that can be shown now (empty when the whole piece is a held start)
     */
    String accept(Terminal terminal, String data, ToolCallState state, long receivedAt) {
        Stream stream = streams.computeIfAbsent(terminal, ignored -> new Stream());
        stream.last = state;
        stream.receivedAt = receivedAt;
        return stream.masker.push(data);
    }

    /** Ends a terminal whose tool call finished: its held rest, masked as it stands. */
    String end(Terminal terminal) {
        Stream stream = streams.remove(terminal);
        return stream == null ? "" : stream.masker.flush();
    }

    /** Forgets the terminals of a finished tool call; their held output must have gone out. */
    void forget(String toolCallId) {
        streams.keySet().removeIf(terminal -> terminal.toolCallId().equals(toolCallId));
    }

    /**
     * Before another event: every held start, masked, in the order the terminals began.
     *
     * @param end whether the event ends the terminals (a run or session boundary); they are forgotten then
     * @param except a terminal whose held output stays (the event carries it itself), or {@code null}
     */
    List<Held> flush(boolean end, Terminal except) {
        List<Held> ready = new ArrayList<>();
        streams.forEach((terminal, stream) -> {
            if (Objects.equals(terminal, except)) {
                return;
            }
            String text = stream.masker.flush();
            if (!text.isEmpty()) {
                ready.add(new Held(ToolCalls.withOnlyTerminalOutput(stream.last, text), stream.receivedAt));
            }
        });
        if (end) {
            streams.keySet().removeIf(terminal -> !terminal.equals(except));
        }
        return ready;
    }
}
