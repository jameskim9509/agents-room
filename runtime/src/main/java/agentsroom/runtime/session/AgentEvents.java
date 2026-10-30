package agentsroom.runtime.session;

import agentsroom.contract.AgentErrorData;
import agentsroom.contract.AgentErrorEvent;
import agentsroom.contract.AgentEvent;
import agentsroom.contract.AgentMessageChunkEvent;
import agentsroom.contract.AgentStartingData;
import agentsroom.contract.AgentStartingEvent;
import agentsroom.contract.AgentStatus;
import agentsroom.contract.AgentStatusChangedData;
import agentsroom.contract.AgentStatusChangedEvent;
import agentsroom.contract.AgentThoughtChunkEvent;
import agentsroom.contract.AuthStatus;
import agentsroom.contract.AuthStatusEvent;
import agentsroom.contract.AvailableCommand;
import agentsroom.contract.AvailableCommandsData;
import agentsroom.contract.AvailableCommandsEvent;
import agentsroom.contract.ConfigOptionsData;
import agentsroom.contract.ConfigOptionsEvent;
import agentsroom.contract.CurrentModeData;
import agentsroom.contract.CurrentModeEvent;
import agentsroom.contract.ErrorCode;
import agentsroom.contract.MessageChunkData;
import agentsroom.contract.PermissionOutcome;
import agentsroom.contract.PermissionRequestedData;
import agentsroom.contract.PermissionRequestedEvent;
import agentsroom.contract.PermissionResolvedData;
import agentsroom.contract.PermissionResolvedEvent;
import agentsroom.contract.PlanData;
import agentsroom.contract.PlanEntry;
import agentsroom.contract.PlanEvent;
import agentsroom.contract.RunEndedData;
import agentsroom.contract.RunEndedEvent;
import agentsroom.contract.RunStartedData;
import agentsroom.contract.RunStartedEvent;
import agentsroom.contract.SessionEndedData;
import agentsroom.contract.SessionEndedEvent;
import agentsroom.contract.SessionInfoData;
import agentsroom.contract.SessionInfoEvent;
import agentsroom.contract.SessionStartedData;
import agentsroom.contract.SessionStartedEvent;
import agentsroom.contract.Stage;
import agentsroom.contract.ToolCallData;
import agentsroom.contract.ToolCallEvent;
import agentsroom.contract.ToolCallState;
import agentsroom.contract.ToolCallUpdateEvent;
import agentsroom.contract.UnknownUpdateData;
import agentsroom.contract.UnknownUpdateEvent;
import agentsroom.contract.UsageData;
import agentsroom.contract.UsageEvent;
import agentsroom.contract.UserMessageChunkEvent;
import agentsroom.runtime.acp.AgentUpdate;
import agentsroom.runtime.logging.SecretMasker;
import java.util.List;
import java.util.Map;

/**
 * Builds the AgentEvent of each kind of ADR 0005 with its {@code data}; the envelope ({@code projectId}
 * to {@code receivedAt}) is set by the agent that emits it. Diagnostic messages are masked here.
 */
final class AgentEvents {

    private AgentEvents() {
    }

    static AgentEvent statusChanged(AgentStatus status, AgentStatus previous) {
        AgentStatusChangedData data = new AgentStatusChangedData();
        data.setStatus(status);
        data.setPrevious(previous);
        AgentStatusChangedEvent event = new AgentStatusChangedEvent();
        event.setData(data);
        return event;
    }

    static AgentEvent starting(Stage stage) {
        AgentStartingData data = new AgentStartingData();
        data.setStage(stage);
        AgentStartingEvent event = new AgentStartingEvent();
        event.setData(data);
        return event;
    }

    static AgentEvent sessionStarted(SessionStartedData data) {
        SessionStartedEvent event = new SessionStartedEvent();
        event.setData(data);
        return event;
    }

    static AgentEvent sessionEnded(SessionEndedData data) {
        SessionEndedEvent event = new SessionEndedEvent();
        event.setData(data);
        return event;
    }

    static AgentErrorData error(ErrorCode code, Stage stage, String message) {
        AgentErrorData data = new AgentErrorData();
        data.setCode(code);
        data.setStage(stage);
        data.setMessage(SecretMasker.mask(message == null ? "" : message));
        return data;
    }

    static AgentEvent agentError(AgentErrorData data) {
        AgentErrorEvent event = new AgentErrorEvent();
        event.setData(data);
        return event;
    }

    static AgentEvent authStatus(AuthStatus status) {
        AuthStatusEvent event = new AuthStatusEvent();
        event.setData(status);
        return event;
    }

    static AgentEvent runStarted(String runId, String prompt) {
        RunStartedData data = new RunStartedData();
        data.setRunId(runId);
        data.setPrompt(prompt);
        RunStartedEvent event = new RunStartedEvent();
        event.setData(data);
        return event;
    }

    static AgentEvent runEnded(RunEndedData data) {
        RunEndedEvent event = new RunEndedEvent();
        event.setData(data);
        return event;
    }

    /** A chunk event; {@code text} must already be masked (the chunk seams do that). */
    static AgentEvent chunk(AgentUpdate.ChunkKind kind, String messageId, String contentType, String text) {
        MessageChunkData data = new MessageChunkData();
        data.setMessageId(messageId);
        data.setContentType(contentType);
        data.setText(text);
        return switch (kind) {
            case USER -> {
                UserMessageChunkEvent event = new UserMessageChunkEvent();
                event.setData(data);
                yield event;
            }
            case AGENT -> {
                AgentMessageChunkEvent event = new AgentMessageChunkEvent();
                event.setData(data);
                yield event;
            }
            case THOUGHT -> {
                AgentThoughtChunkEvent event = new AgentThoughtChunkEvent();
                event.setData(data);
                yield event;
            }
        };
    }

    static AgentEvent toolCall(boolean initial, ToolCallState state) {
        ToolCallData data = new ToolCallData();
        data.setToolCall(state);
        if (initial) {
            ToolCallEvent event = new ToolCallEvent();
            event.setData(data);
            return event;
        }
        ToolCallUpdateEvent event = new ToolCallUpdateEvent();
        event.setData(data);
        return event;
    }

    static AgentEvent plan(List<PlanEntry> entries) {
        PlanData data = new PlanData();
        data.setEntries(entries);
        PlanEvent event = new PlanEvent();
        event.setData(data);
        return event;
    }

    static AgentEvent availableCommands(List<AvailableCommand> commands) {
        AvailableCommandsData data = new AvailableCommandsData();
        data.setCommands(commands);
        AvailableCommandsEvent event = new AvailableCommandsEvent();
        event.setData(data);
        return event;
    }

    static AgentEvent currentMode(String currentModeId) {
        CurrentModeData data = new CurrentModeData();
        data.setCurrentModeId(currentModeId);
        CurrentModeEvent event = new CurrentModeEvent();
        event.setData(data);
        return event;
    }

    static AgentEvent configOptions(List<Map<String, Object>> options) {
        ConfigOptionsData data = new ConfigOptionsData();
        data.setConfigOptions(options);
        ConfigOptionsEvent event = new ConfigOptionsEvent();
        event.setData(data);
        return event;
    }

    static AgentEvent sessionInfo(SessionInfoData data) {
        SessionInfoEvent event = new SessionInfoEvent();
        event.setData(data);
        return event;
    }

    static AgentEvent usage(UsageData data) {
        UsageEvent event = new UsageEvent();
        event.setData(data);
        return event;
    }

    static AgentEvent unknown(String type) {
        UnknownUpdateData data = new UnknownUpdateData();
        data.setType(type);
        UnknownUpdateEvent event = new UnknownUpdateEvent();
        event.setData(data);
        return event;
    }

    static AgentEvent permissionRequested(PermissionRequestedData data) {
        PermissionRequestedEvent event = new PermissionRequestedEvent();
        event.setData(data);
        return event;
    }

    /** @param optionId only for {@code selected} (the contract forbids it otherwise) */
    static AgentEvent permissionResolved(String permissionId, PermissionOutcome outcome, String optionId) {
        PermissionResolvedData data = new PermissionResolvedData();
        data.setPermissionId(permissionId);
        data.setOutcome(outcome);
        if (outcome == PermissionOutcome.SELECTED) {
            data.setOptionId(optionId);
        }
        PermissionResolvedEvent event = new PermissionResolvedEvent();
        event.setData(data);
        return event;
    }
}
