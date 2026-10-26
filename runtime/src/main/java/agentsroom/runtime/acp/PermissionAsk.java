package agentsroom.runtime.acp;

import agentsroom.contract.PermissionOption;
import java.util.List;

/**
 * An ACP {@code session/request_permission}, masked. Option ids stay as received: the answer must name
 * one of them (ADR 0005 decision 7).
 *
 * @param toolCall the tool call the adapter asks about, as a partial report
 * @param options the options the user may pick; an option of a kind the contract does not know is left
 *     out, so it can never be chosen
 */
public record PermissionAsk(String sessionId, AgentUpdate.ToolCallReport toolCall, List<PermissionOption> options) {

    public PermissionAsk {
        options = List.copyOf(options);
    }

    /** Whether {@code optionId} is one of {@link #options()}. */
    public boolean hasOption(String optionId) {
        return options.stream().anyMatch(option -> option.getOptionId().equals(optionId));
    }
}
