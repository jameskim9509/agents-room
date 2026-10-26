package agentsroom.runtime.session;

import agentsroom.contract.ToolCallState;
import agentsroom.contract.ToolCallStatus;
import agentsroom.runtime.acp.AgentUpdate;
import java.util.ArrayList;
import java.util.List;

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

    /** Whether the adapter reported the tool call finished ({@code completed} or {@code failed}). */
    static boolean isFinished(ToolCallState state) {
        return state.getStatus() == ToolCallStatus.COMPLETED || state.getStatus() == ToolCallStatus.FAILED;
    }
}
