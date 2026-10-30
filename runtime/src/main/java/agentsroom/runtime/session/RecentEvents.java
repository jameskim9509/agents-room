package agentsroom.runtime.session;

import agentsroom.contract.AgentEvent;
import agentsroom.contract.AgentMessageChunkEvent;
import agentsroom.contract.AgentThoughtChunkEvent;
import agentsroom.contract.MessageChunkData;
import agentsroom.contract.UserMessageChunkEvent;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Objects;

/**
 * The in-memory event tail of one agent, for the snapshot's {@code recentEvents} (ADR 0005 decision 2,
 * D24): oldest first, at most {@value #LIMIT} events, never persisted.
 *
 * <ul>
 *   <li>Thought chunks are left out: they are live only (ux.md, SCRUM-16).
 *   <li>Consecutive text chunks of one kind and one {@code messageId} (or, when that is absent, of one
 *       run) become one event with the joined text and the last chunk's {@code seq} and
 *       {@code receivedAt}. The texts are already masked across the chunk seams (ADR 0005 decision 11),
 *       so the joined text is exactly what the live events showed, one after the other.
 * </ul>
 */
final class RecentEvents {

    static final int LIMIT = 200;

    private static final String TEXT = "text";

    private final Deque<AgentEvent> events = new ArrayDeque<>();

    void add(AgentEvent event) {
        if (event instanceof AgentThoughtChunkEvent) {
            return;
        }
        AgentEvent last = events.peekLast();
        if (last != null && continues(last, event)) {
            events.removeLast();
            events.addLast(joined(last, event));
            return;
        }
        events.addLast(event);
        while (events.size() > LIMIT) {
            events.removeFirst();
        }
    }

    List<AgentEvent> list() {
        return List.copyOf(events);
    }

    void clear() {
        events.clear();
    }

    private static boolean continues(AgentEvent last, AgentEvent next) {
        MessageChunkData previous = textChunk(last);
        MessageChunkData current = textChunk(next);
        if (previous == null || current == null || last.getClass() != next.getClass()) {
            return false;
        }
        if (previous.getMessageId() != null || current.getMessageId() != null) {
            return Objects.equals(previous.getMessageId(), current.getMessageId());
        }
        return Objects.equals(last.getRunId(), next.getRunId());
    }

    private static AgentEvent joined(AgentEvent last, AgentEvent next) {
        MessageChunkData data = new MessageChunkData();
        data.setMessageId(textChunk(next).getMessageId());
        data.setContentType(TEXT);
        data.setText(textChunk(last).getText() + textChunk(next).getText());
        AgentEvent merged;
        if (next instanceof UserMessageChunkEvent) {
            UserMessageChunkEvent user = new UserMessageChunkEvent();
            user.setData(data);
            merged = user;
        } else {
            AgentMessageChunkEvent agent = new AgentMessageChunkEvent();
            agent.setData(data);
            merged = agent;
        }
        merged.setProjectId(next.getProjectId());
        merged.setAgentId(next.getAgentId());
        merged.setSessionId(next.getSessionId());
        merged.setRunId(next.getRunId());
        merged.setSeq(next.getSeq());
        merged.setReceivedAt(next.getReceivedAt());
        return merged;
    }

    /** The data of a user or agent text chunk, else {@code null}. */
    private static MessageChunkData textChunk(AgentEvent event) {
        MessageChunkData data = switch (event) {
            case UserMessageChunkEvent user -> user.getData();
            case AgentMessageChunkEvent agent -> agent.getData();
            default -> null;
        };
        return data != null && TEXT.equals(data.getContentType()) && data.getText() != null ? data : null;
    }
}
