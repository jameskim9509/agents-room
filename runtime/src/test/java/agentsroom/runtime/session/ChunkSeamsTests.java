package agentsroom.runtime.session;

import static org.assertj.core.api.Assertions.assertThat;

import agentsroom.runtime.acp.AgentUpdate.ChunkKind;
import agentsroom.runtime.session.ChunkSeams.Text;
import java.util.List;
import org.junit.jupiter.api.Test;

/** One agent's text streams across chunk seams (ADR 0005 decision 11). Fake values only. */
class ChunkSeamsTests {

    private static final String KEY = String.join("-", "sk", "mockmockmockmock1234");

    private final ChunkSeams seams = new ChunkSeams();

    @Test
    void aKeySplitAcrossTheChunksOfAMessageIsMaskedAndTheTextComesInOrder() {
        assertThat(texts(seams.accept(ChunkKind.AGENT, "m1", "the key " + KEY.substring(0, 4), 1)))
                .containsExactly("the key ");
        assertThat(seams.accept(ChunkKind.AGENT, "m1", KEY.substring(4, 9), 2)).as("all of it may start the key")
                .isEmpty();
        assertThat(seams.accept(ChunkKind.AGENT, "m1", KEY.substring(9) + " stays hidden.", 3))
                .containsExactly(new Text(ChunkKind.AGENT, "m1", "**** stays hidden.", 3));
        // A key that is recognizable already is masked at once, and so is its rest in the next chunk.
        assertThat(texts(seams.accept(ChunkKind.AGENT, "m1", " Again " + KEY.substring(0, 12), 4)))
                .containsExactly(" Again ****");
        assertThat(texts(seams.accept(ChunkKind.AGENT, "m1", KEY.substring(12) + ".", 5))).containsExactly(".");
    }

    @Test
    void anotherMessageOfTheKindEndsThePreviousOneFirst() {
        seams.accept(ChunkKind.AGENT, "m1", "the key sk-moc", 1);

        assertThat(seams.accept(ChunkKind.AGENT, "m2", "Next.", 2)).containsExactly(
                new Text(ChunkKind.AGENT, "m1", "sk-moc", 1),
                new Text(ChunkKind.AGENT, "m2", "Next.", 2));
    }

    @Test
    void aThoughtBetweenTwoChunksLeavesTheMessageStreamAsItIs() {
        assertThat(texts(seams.accept(ChunkKind.AGENT, "m1", "the key sk-moc", 1))).containsExactly("the key ");
        assertThat(texts(seams.accept(ChunkKind.THOUGHT, "t1", "Thinking.", 2))).containsExactly("Thinking.");
        assertThat(texts(seams.accept(ChunkKind.AGENT, "m1", KEY.substring(6) + " end", 3)))
                .containsExactly("**** end");
    }

    @Test
    void anotherEventTakesTheHeldStartsFirstAndTheStreamGoesOn() {
        seams.accept(ChunkKind.AGENT, "m1", "a token", 1);
        seams.accept(ChunkKind.AGENT, "m1", " and sk-moc", 2);
        seams.accept(ChunkKind.USER, null, "prompt AKIA", 3);

        assertThat(seams.flush(false)).containsExactly(new Text(ChunkKind.AGENT, "m1", "sk-moc", 2),
                new Text(ChunkKind.USER, null, "AKIA", 3));
        assertThat(seams.flush(false)).as("nothing is held twice").isEmpty();
        // The stream goes on with its context: a key name before the event still masks its value after it.
        assertThat(texts(seams.accept(ChunkKind.AGENT, "m1", "kmock token=abc", 4))).containsExactly("**** token=****");
    }

    @Test
    void aRunOrSessionBoundaryForgetsTheStreams() {
        seams.accept(ChunkKind.AGENT, null, "id sk-mo", 1);

        assertThat(texts(seams.flush(true))).containsExactly("sk-mo");
        assertThat(texts(seams.accept(ChunkKind.AGENT, null, "ckmockmock", 2))).as("a new stream")
                .containsExactly("ckmockmock");
    }

    private static List<String> texts(List<Text> texts) {
        return texts.stream().map(Text::text).toList();
    }
}
