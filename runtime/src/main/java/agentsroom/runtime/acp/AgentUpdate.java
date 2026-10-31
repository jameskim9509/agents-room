package agentsroom.runtime.acp;

import agentsroom.contract.AvailableCommand;
import agentsroom.contract.PlanEntry;
import agentsroom.contract.SessionInfoData;
import agentsroom.contract.ToolCallLocation;
import agentsroom.contract.ToolCallStatus;
import agentsroom.contract.ToolKind;
import agentsroom.contract.UsageData;
import java.util.List;
import java.util.Map;

/**
 * One ACP {@code session/update}, translated at the port (ADR 0003 decision 4): no SDK type passes it,
 * and every adapter-derived string in it is already masked (ADR 0004 decision 8), except a chunk's text
 * ({@link Chunk}) and a piece of command output ({@link TerminalOutput}). Each variant maps to one content
 * kind of ADR 0005; an update the SDK does not model is {@link Unknown}.
 */
public sealed interface AgentUpdate {

    /** Which ACP chunk a {@link Chunk} is. */
    enum ChunkKind {
        USER, AGENT, THOUGHT
    }

    /**
     * {@code user_message_chunk}, {@code agent_message_chunk}, {@code agent_thought_chunk}.
     *
     * <p>The one value that is not masked at the port: a secret can be split across chunks, so the text
     * is masked across the chunk seams of its message before any event carries it (the session's chunk
     * seams, ADR 0005 decision 11). Never put it into a frame as it is.
     *
     * @param messageId the adapter's message id, as received, or {@code null}
     * @param contentType the ACP content block type ({@code text}, {@code image}, ...)
     * @param text the raw text of a {@code text} block, else {@code null}
     */
    record Chunk(ChunkKind kind, String messageId, String contentType, String text) implements AgentUpdate {
    }

    /** {@code plan}: the whole plan, replacing the previous one. */
    record Plan(List<PlanEntry> entries) implements AgentUpdate {

        public Plan {
            entries = List.copyOf(entries);
        }
    }

    /** {@code available_commands_update}. */
    record AvailableCommands(List<AvailableCommand> commands) implements AgentUpdate {

        public AvailableCommands {
            commands = List.copyOf(commands);
        }
    }

    /** {@code current_mode_update}. */
    record CurrentMode(String currentModeId) implements AgentUpdate {
    }

    /** {@code config_option_update}: the opaque option list. */
    record ConfigOptions(List<Map<String, Object>> configOptions) implements AgentUpdate {
    }

    /** {@code session_info_update}. */
    record SessionInfo(SessionInfoData info) implements AgentUpdate {
    }

    /** {@code usage_update}. */
    record Usage(UsageData usage) implements AgentUpdate {
    }

    /** An update the runtime does not know; only its type name is kept (SCRUM-50). */
    record Unknown(String type) implements AgentUpdate {
    }

    /**
     * {@code tool_call} ({@code initial}) or {@code tool_call_update}. A {@code null} field was not
     * reported: for an update it leaves the merged value as it was (ADR 0005 decision 6). {@code kind}
     * and {@code status} are {@code null} also when the adapter sent a value the contract does not
     * know for {@code status}; an unknown {@code kind} arrives as {@code other} (the SDK reads it so).
     *
     * @param content the masked ACP ToolCallContent list
     * @param rawInput masked JSON value
     * @param rawOutput masked JSON value
     * @param meta the masked {@code _meta} of this message; with {@code terminalOutput}, its
     *     {@code terminal_output.data} is empty
     * @param terminalOutput the piece of command output this message carries in
     *     {@code _meta.terminal_output}, unmasked, or {@code null}
     */
    record ToolCallReport(
            boolean initial,
            String toolCallId,
            String title,
            ToolKind kind,
            ToolCallStatus status,
            List<Map<String, Object>> content,
            List<ToolCallLocation> locations,
            Object rawInput,
            Object rawOutput,
            Map<String, Object> meta,
            TerminalOutput terminalOutput) implements AgentUpdate {

        /** A report without a piece of command output. */
        public ToolCallReport(boolean initial, String toolCallId, String title, ToolKind kind, ToolCallStatus status,
                List<Map<String, Object>> content, List<ToolCallLocation> locations, Object rawInput,
                Object rawOutput, Map<String, Object> meta) {
            this(initial, toolCallId, title, kind, status, content, locations, rawInput, rawOutput, meta, null);
        }
    }

    /**
     * One piece of a command's output, as codex-acp sends it in {@code _meta.terminal_output} (the client
     * declares {@code terminal_output}): {@code {terminal_id, data}}, one piece per tool call update.
     *
     * <p>Like a chunk's text, {@code data} is not masked at the port: a secret can be split across pieces,
     * so the session masks the output of one terminal across its pieces before any event carries it (ADR
     * 0005 decision 11). Never put it into a frame as it is.
     *
     * @param terminalId the adapter's terminal id, as received ({@code ""} when it sent none)
     * @param data the raw piece of output
     */
    record TerminalOutput(String terminalId, String data) {

        /** The {@code _meta} key of the pieces, and the keys of its object. */
        public static final String META_KEY = "terminal_output";
        public static final String TERMINAL_ID = "terminal_id";
        public static final String DATA = "data";
    }
}
