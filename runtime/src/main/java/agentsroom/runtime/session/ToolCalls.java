package agentsroom.runtime.session;

import agentsroom.contract.ToolCallState;
import agentsroom.contract.ToolCallStatus;
import agentsroom.runtime.acp.AgentUpdate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The tool call merge rule of ADR 0005 decision 6. A report updates the fields it carries; the others
 * keep their merged value. {@code content} and {@code locations} replace the previous list, {@code
 * rawInput} and {@code rawOutput} change when reported, and {@code vendorMeta} is the {@code _meta} of
 * the latest message exactly (not merged). A missing {@code kind} or {@code status} stays absent: the
 * ACP defaults ({@code other}, {@code pending}) are never stored as if reported.
 */
final class ToolCalls {

    private ToolCalls() {
    }

    /**
     * The state after {@code report}, as a new object: earlier states stay as they were, since events
     * that carry them are kept in the recent-events tail.
     *
     * @param previous the merged state so far, or {@code null} for a tool call not seen yet
     */
    static ToolCallState merge(ToolCallState previous, AgentUpdate.ToolCallReport report) {
        ToolCallState next = new ToolCallState();
        next.setToolCallId(report.toolCallId());
        next.setTitle(report.title() != null ? report.title() : previous == null ? null : previous.getTitle());
        next.setKind(report.kind() != null ? report.kind() : previous == null ? null : previous.getKind());
        next.setStatus(report.status() != null ? report.status() : previous == null ? null : previous.getStatus());
        if (report.locations() != null) {
            next.setLocations(new ArrayList<>(report.locations()));
        } else {
            next.setLocations(previous == null || previous.getLocations() == null ? List.of() : previous.getLocations());
        }
        next.setContent(report.content() != null ? report.content() : previous == null ? null : previous.getContent());
        next.setRawInput(report.rawInput() != null ? report.rawInput() : previous == null ? null : previous.getRawInput());
        next.setRawOutput(
                report.rawOutput() != null ? report.rawOutput() : previous == null ? null : previous.getRawOutput());
        next.setVendorMeta(report.meta());
        return next;
    }

    /**
     * {@code state} with {@code text} as its {@code vendorMeta.terminal_output.data}: the masked output of
     * a piece (ADR 0005 decision 11), which the port left empty.
     */
    static ToolCallState withTerminalOutput(ToolCallState state, String text) {
        Map<String, Object> meta =
                state.getVendorMeta() == null ? new LinkedHashMap<>() : new LinkedHashMap<>(state.getVendorMeta());
        meta.put(AgentUpdate.TerminalOutput.META_KEY, outputWith(meta.get(AgentUpdate.TerminalOutput.META_KEY), text));
        ToolCallState next = copy(state);
        next.setVendorMeta(meta);
        return next;
    }

    /**
     * {@code state} with only {@code text} as its {@code vendorMeta}, in {@code terminal_output}: the update
     * that carries held output of a terminal on its own.
     */
    static ToolCallState withOnlyTerminalOutput(ToolCallState state, String text) {
        Map<String, Object> meta = new LinkedHashMap<>();
        Object output = state.getVendorMeta() == null ? null
                : state.getVendorMeta().get(AgentUpdate.TerminalOutput.META_KEY);
        meta.put(AgentUpdate.TerminalOutput.META_KEY, outputWith(output, text));
        ToolCallState next = copy(state);
        next.setVendorMeta(meta);
        return next;
    }

    private static Map<Object, Object> outputWith(Object output, String text) {
        Map<Object, Object> copy = output instanceof Map<?, ?> map ? new LinkedHashMap<>(map) : new LinkedHashMap<>();
        copy.put(AgentUpdate.TerminalOutput.DATA, text);
        return copy;
    }

    private static ToolCallState copy(ToolCallState state) {
        ToolCallState copy = new ToolCallState();
        copy.setToolCallId(state.getToolCallId());
        copy.setTitle(state.getTitle());
        copy.setKind(state.getKind());
        copy.setStatus(state.getStatus());
        copy.setLocations(state.getLocations());
        copy.setContent(state.getContent());
        copy.setRawInput(state.getRawInput());
        copy.setRawOutput(state.getRawOutput());
        copy.setVendorMeta(state.getVendorMeta());
        return copy;
    }

    /** Whether the adapter reported the tool call finished ({@code completed} or {@code failed}). */
    static boolean isFinished(ToolCallState state) {
        return state.getStatus() == ToolCallStatus.COMPLETED || state.getStatus() == ToolCallStatus.FAILED;
    }
}
