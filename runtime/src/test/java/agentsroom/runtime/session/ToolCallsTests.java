package agentsroom.runtime.session;

import static org.assertj.core.api.Assertions.assertThat;

import agentsroom.contract.ToolCallLocation;
import agentsroom.contract.ToolCallState;
import agentsroom.contract.ToolCallStatus;
import agentsroom.contract.ToolKind;
import agentsroom.runtime.acp.AgentUpdate.ToolCallReport;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The tool call merge rule of ADR 0005 decision 6. */
class ToolCallsTests {

    @Test
    void anUpdateChangesOnlyWhatItReports() {
        ToolCallState first = ToolCalls.merge(null, report(true, "Read README.md", ToolKind.READ, ToolCallStatus.PENDING,
                List.of(location("README.md")), Map.of("path", "README.md"), null, Map.of("a", 1)));

        ToolCallState next = ToolCalls.merge(first, report(false, null, null, ToolCallStatus.COMPLETED, null, null,
                "# demo", null));

        assertThat(next.getTitle()).isEqualTo("Read README.md");
        assertThat(next.getKind()).isEqualTo(ToolKind.READ);
        assertThat(next.getStatus()).isEqualTo(ToolCallStatus.COMPLETED);
        assertThat(next.getLocations()).extracting(ToolCallLocation::getPath).containsExactly("README.md");
        assertThat(next.getRawInput()).isEqualTo(Map.of("path", "README.md"));
        assertThat(next.getRawOutput()).isEqualTo("# demo");
        assertThat(next.getVendorMeta()).as("the latest message's _meta, not merged").isNull();
        assertThat(first.getStatus()).as("an earlier state is never changed").isEqualTo(ToolCallStatus.PENDING);
    }

    @Test
    void listsAreReplacedNotMerged() {
        ToolCallState first = ToolCalls.merge(null, report(true, "Edit", ToolKind.EDIT, null,
                List.of(location("a.txt"), location("b.txt")), null, null, null));

        ToolCallState next = ToolCalls.merge(first, report(false, null, null, null, List.of(location("c.txt")), null,
                null, Map.of("terminal_exit", Map.of("exit_code", 0))));

        assertThat(next.getLocations()).extracting(ToolCallLocation::getPath).containsExactly("c.txt");
        assertThat(next.getVendorMeta()).containsKey("terminal_exit");
    }

    @Test
    void neverFillsInTheAcpDefaults() {
        ToolCallState state = ToolCalls.merge(null, report(true, "Something", null, null, null, null, null, null));

        assertThat(state.getKind()).as("ACP's default 'other' is not stored as reported (R6)").isNull();
        assertThat(state.getStatus()).as("ACP's default 'pending' is not stored as reported").isNull();
        assertThat(state.getLocations()).as("required on the wire").isEmpty();
        assertThat(ToolCalls.isFinished(state)).isFalse();
    }

    @Test
    void anUpdateForAToolCallNotSeenStartsFromNothing() {
        ToolCallState state = ToolCalls.merge(null, report(false, null, null, ToolCallStatus.FAILED, null, null, null,
                null));

        assertThat(state.getToolCallId()).isEqualTo("call-1");
        assertThat(state.getTitle()).isNull();
        assertThat(ToolCalls.isFinished(state)).isTrue();
    }

    private static ToolCallReport report(boolean initial, String title, ToolKind kind, ToolCallStatus status,
            List<ToolCallLocation> locations, Object rawInput, Object rawOutput, Map<String, Object> meta) {
        return new ToolCallReport(initial, "call-1", title, kind, status, null, locations, rawInput, rawOutput, meta);
    }

    private static ToolCallLocation location(String path) {
        ToolCallLocation location = new ToolCallLocation();
        location.setPath(path);
        return location;
    }
}
