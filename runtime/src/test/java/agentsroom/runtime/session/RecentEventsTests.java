package agentsroom.runtime.session;

import static org.assertj.core.api.Assertions.assertThat;

import agentsroom.contract.AgentEvent;
import agentsroom.contract.AgentMessageChunkEvent;
import agentsroom.contract.Stage;
import agentsroom.runtime.acp.AgentUpdate.ChunkKind;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The snapshot's event tail (ADR 0005 decision 2, D24). */
class RecentEventsTests {

    private final RecentEvents recent = new RecentEvents();
    private long seq;

    @Test
    void joinsConsecutiveTextChunksOfOneMessage() {
        recent.add(chunk(ChunkKind.AGENT, "m1", "run", "Hello, "));
        recent.add(chunk(ChunkKind.AGENT, "m1", "run", "world"));
        recent.add(chunk(ChunkKind.AGENT, "m2", "run", "Next"));

        List<AgentEvent> events = recent.list();
        assertThat(events).hasSize(2);
        AgentMessageChunkEvent joined = (AgentMessageChunkEvent) events.getFirst();
        assertThat(joined.getData().getText()).isEqualTo("Hello, world");
        assertThat(joined.getData().getMessageId()).isEqualTo("m1");
        assertThat(joined.getSeq()).as("the last chunk's seq").isEqualTo(2);
    }

    @Test
    void withoutAMessageIdTheRunDecides() {
        recent.add(chunk(ChunkKind.AGENT, null, "run-1", "a"));
        recent.add(chunk(ChunkKind.AGENT, null, "run-1", "b"));
        recent.add(chunk(ChunkKind.AGENT, null, "run-2", "c"));
        recent.add(chunk(ChunkKind.USER, null, "run-2", "d"));

        assertThat(recent.list()).hasSize(3);
    }

    @Test
    void leavesThoughtsOutAndJoinsAcrossThem() {
        recent.add(chunk(ChunkKind.AGENT, "m1", "run", "a"));
        recent.add(chunk(ChunkKind.THOUGHT, "t1", "run", "thinking"));
        recent.add(chunk(ChunkKind.AGENT, "m1", "run", "b"));

        assertThat(recent.list()).singleElement().satisfies(event -> assertThat(
                ((AgentMessageChunkEvent) event).getData().getText()).isEqualTo("ab"));
    }

    @Test
    void anotherEventInBetweenEndsTheJoin() {
        recent.add(chunk(ChunkKind.AGENT, "m1", "run", "a"));
        recent.add(envelope(AgentEvents.starting(Stage.SPAWN), "run"));
        recent.add(chunk(ChunkKind.AGENT, "m1", "run", "b"));

        assertThat(recent.list()).hasSize(3);
    }

    @Test
    void theJoinedTextIsTheLiveTextsAsTheyWereSent() {
        // The chunk seams masked the live texts already (ADR 0005 decision 11): the tail adds nothing.
        recent.add(chunk(ChunkKind.AGENT, "m1", "run", "key "));
        recent.add(chunk(ChunkKind.AGENT, "m1", "run", "**** end"));
        recent.add(chunk(ChunkKind.AGENT, "m1", "run", " token=x"));

        String text = ((AgentMessageChunkEvent) recent.list().getFirst()).getData().getText();
        assertThat(text).isEqualTo("key **** end token=x");
    }

    @Test
    void keepsTheNewest200() {
        for (int i = 0; i < 250; i++) {
            recent.add(envelope(AgentEvents.starting(Stage.SPAWN), null));
        }

        List<AgentEvent> events = recent.list();
        assertThat(events).hasSize(RecentEvents.LIMIT);
        assertThat(events.getFirst().getSeq()).isEqualTo(51);
        assertThat(events.getLast().getSeq()).isEqualTo(250);
    }

    private AgentEvent chunk(ChunkKind kind, String messageId, String runId, String text) {
        return envelope(AgentEvents.chunk(kind, messageId, "text", text), runId);
    }

    private AgentEvent envelope(AgentEvent event, String runId) {
        event.setProjectId("p");
        event.setAgentId("a");
        event.setRunId(runId);
        event.setSeq(++seq);
        event.setReceivedAt(seq);
        return event;
    }
}
