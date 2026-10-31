package agentsroom.runtime.acp;

import agentsroom.contract.AgentInfo;
import agentsroom.contract.AuthMethodInfo;
import agentsroom.contract.AuthStatus;
import agentsroom.contract.AvailableCommand;
import agentsroom.contract.Cost;
import agentsroom.contract.PermissionOption;
import agentsroom.contract.PermissionOptionKind;
import agentsroom.contract.PlanEntry;
import agentsroom.contract.SessionInfoData;
import agentsroom.contract.ToolCallLocation;
import agentsroom.contract.ToolCallStatus;
import agentsroom.contract.ToolKind;
import agentsroom.contract.UsageData;
import com.agentclientprotocol.sdk.json.AcpJsonMapper;
import com.agentclientprotocol.sdk.json.TypeRef;
import com.agentclientprotocol.sdk.spec.AcpSchema;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Turns ACP Java SDK records into the port's values (ADR 0003 decision 4): the only class above the
 * transport that knows the SDK's schema types. Every adapter-derived string is masked here
 * ({@link AdapterValues}); ids (session, tool call, permission option) stay as received, because they
 * are opaque keys the runtime must hand back. A chunk's text and a piece of command output
 * ({@code _meta.terminal_output.data}) are the exceptions: only the session sees the pieces before them, so
 * it masks that text across the seams of its message or terminal (ADR 0005 decision 11).
 */
final class AcpTranslator {

    private static final Logger log = LoggerFactory.getLogger(AcpTranslator.class);

    private static final TypeRef<Map<String, Object>> JSON_OBJECT = new TypeRef<>() {
    };

    /** What an {@code _auth/status_update} carries its status in (codex-acp, claude-agent-acp). */
    private static final String AUTH_STATUS = "authStatus";

    private final AcpJsonMapper mapper;

    AcpTranslator(AcpJsonMapper mapper) {
        this.mapper = mapper;
    }

    AgentUpdate update(AcpSchema.SessionUpdate update) {
        return switch (update) {
            case AcpSchema.UserMessageChunk chunk -> chunk(AgentUpdate.ChunkKind.USER, chunk.content(), chunk.messageId());
            case AcpSchema.AgentMessageChunk chunk ->
                    chunk(AgentUpdate.ChunkKind.AGENT, chunk.content(), chunk.messageId());
            case AcpSchema.AgentThoughtChunk chunk ->
                    chunk(AgentUpdate.ChunkKind.THOUGHT, chunk.content(), chunk.messageId());
            case AcpSchema.ToolCall call -> toolCall(true, call.toolCallId(), call.title(), call.kind(),
                    call.status(), call.content(), call.locations(), call.rawInput(), call.rawOutput(), call.meta(),
                    true);
            case AcpSchema.ToolCallUpdateNotification call -> toolCall(false, call.toolCallId(), call.title(),
                    call.kind(), call.status(), call.content(), call.locations(), call.rawInput(), call.rawOutput(),
                    call.meta(), true);
            case AcpSchema.Plan plan -> new AgentUpdate.Plan(plan.entries() == null ? List.of()
                    : plan.entries().stream().map(AcpTranslator::planEntry).toList());
            case AcpSchema.AvailableCommandsUpdate commands -> new AgentUpdate.AvailableCommands(
                    commands.availableCommands() == null ? List.of()
                            : commands.availableCommands().stream().map(AcpTranslator::command).toList());
            case AcpSchema.CurrentModeUpdate mode -> new AgentUpdate.CurrentMode(AdapterValues.text(mode.currentModeId()));
            case AcpSchema.ConfigOptionUpdate options -> new AgentUpdate.ConfigOptions(configOptions(options.configOptions()));
            case AcpSchema.SessionInfoUpdate info -> new AgentUpdate.SessionInfo(sessionInfo(info));
            case AcpSchema.UsageUpdate usage -> new AgentUpdate.Usage(usage(usage));
            case AcpSchema.UnknownSessionUpdate unknown -> new AgentUpdate.Unknown(typeName(unknown.sessionUpdate()));
            // SessionUpdate is not sealed: a type a later SDK adds is unknown here too.
            default -> new AgentUpdate.Unknown(update.getClass().getSimpleName());
        };
    }

    PermissionAsk permission(AcpSchema.RequestPermissionRequest request) {
        AcpSchema.ToolCallUpdate call = request.toolCall();
        AgentUpdate.ToolCallReport toolCall = call == null
                ? new AgentUpdate.ToolCallReport(false, "", null, null, null, null, null, null, null, null)
                : toolCall(false, call.toolCallId(), call.title(), call.kind(), call.status(), call.content(),
                        call.locations(), call.rawInput(), call.rawOutput(), call.meta(), false);
        List<PermissionOption> options = new ArrayList<>();
        if (request.options() != null) {
            for (AcpSchema.PermissionOption option : request.options()) {
                optionKind(option.kind()).ifPresentOrElse(kind -> {
                    PermissionOption translated = new PermissionOption();
                    translated.setOptionId(option.optionId());
                    translated.setName(AdapterValues.text(option.name()));
                    translated.setKind(kind);
                    options.add(translated);
                }, () -> log.warn("Left out a permission option of a kind the contract does not know"));
            }
        }
        return new PermissionAsk(request.sessionId(), toolCall, options);
    }

    /**
     * The status of an {@code _auth/status_update}: {@code {authStatus: {kind, label, account?}}}.
     * Only {@code kind} and {@code label} are kept; {@code account} (email, plan) is identity and is
     * dropped here (ADR 0004 decision 9).
     */
    static Optional<AuthStatus> authStatus(Object params) {
        if (!(params instanceof Map<?, ?> map) || !(map.get(AUTH_STATUS) instanceof Map<?, ?> status)) {
            return Optional.empty();
        }
        if (!(status.get("kind") instanceof String kind)) {
            return Optional.empty();
        }
        AuthStatus result = new AuthStatus();
        result.setKind(AdapterValues.text(kind));
        result.setLabel(status.get("label") instanceof String label ? AdapterValues.text(label) : "");
        return Optional.of(result);
    }

    static AgentInfo agentInfo(AcpSchema.Implementation implementation) {
        if (implementation == null || implementation.name() == null) {
            return null;
        }
        AgentInfo info = new AgentInfo();
        info.setName(AdapterValues.text(implementation.name()));
        info.setVersion(implementation.version() == null ? "" : AdapterValues.text(implementation.version()));
        info.setTitle(AdapterValues.text(implementation.title()));
        return info;
    }

    static AuthMethodInfo authMethod(AcpSchema.AuthMethod method) {
        AuthMethodInfo info = new AuthMethodInfo();
        info.setId(method.id());
        info.setName(method.name() == null ? method.id() : AdapterValues.text(method.name()));
        info.setDescription(AdapterValues.text(method.description()));
        return info;
    }

    Map<String, Object> modes(AcpSchema.SessionModeState modes) {
        return modes == null ? null : AdapterValues.object(mapper.convertValue(modes, JSON_OBJECT));
    }

    List<Map<String, Object>> configOptions(List<AcpSchema.SessionConfigOption> options) {
        if (options == null) {
            return null;
        }
        List<Map<String, Object>> result = new ArrayList<>(options.size());
        options.forEach(option -> result.add(AdapterValues.object(mapper.convertValue(option, JSON_OBJECT))));
        return result;
    }

    /**
     * @param streamsOutput whether a piece of command output in {@code _meta.terminal_output} stays raw for
     *     the session to mask across its pieces (session updates); else it is masked here, on its own (the
     *     tool call of a permission request)
     */
    private AgentUpdate.ToolCallReport toolCall(boolean initial, String toolCallId, String title,
            AcpSchema.ToolKind kind, AcpSchema.ToolCallStatus status, List<AcpSchema.ToolCallContent> content,
            List<AcpSchema.ToolCallLocation> locations, Object rawInput, Object rawOutput, Map<String, Object> meta,
            boolean streamsOutput) {
        AgentUpdate.TerminalOutput output = streamsOutput ? terminalOutput(meta) : null;
        return new AgentUpdate.ToolCallReport(initial, toolCallId, AdapterValues.text(title), kind(kind),
                status(status), content(content), locations(locations), AdapterValues.tree(json(rawInput)),
                AdapterValues.tree(json(rawOutput)), AdapterValues.object(output == null ? meta : withoutData(meta)),
                output);
    }

    /**
     * The piece of command output in {@code _meta.terminal_output} ({@code {terminal_id, data}}, codex-acp
     * for a client that declares {@code terminal_output}), unmasked, or {@code null}.
     */
    private static AgentUpdate.TerminalOutput terminalOutput(Map<String, Object> meta) {
        if (meta == null || !(meta.get(AgentUpdate.TerminalOutput.META_KEY) instanceof Map<?, ?> output)
                || !(output.get(AgentUpdate.TerminalOutput.DATA) instanceof String data)) {
            return null;
        }
        Object terminalId = output.get(AgentUpdate.TerminalOutput.TERMINAL_ID);
        return new AgentUpdate.TerminalOutput(terminalId == null ? "" : String.valueOf(terminalId), data);
    }

    /** {@code meta} with an empty {@code terminal_output.data}: the session fills in the masked text. */
    private static Map<String, Object> withoutData(Map<String, Object> meta) {
        Map<String, Object> copy = new LinkedHashMap<>(meta);
        Map<Object, Object> output = new LinkedHashMap<>((Map<?, ?>) meta.get(AgentUpdate.TerminalOutput.META_KEY));
        output.put(AgentUpdate.TerminalOutput.DATA, "");
        copy.put(AgentUpdate.TerminalOutput.META_KEY, output);
        return copy;
    }

    /** The text stays raw: the session masks it across the chunk seams (ADR 0005 decision 11). */
    private static AgentUpdate.Chunk chunk(AgentUpdate.ChunkKind kind, AcpSchema.ContentBlock content,
            String messageId) {
        return switch (content) {
            case AcpSchema.TextContent text ->
                    new AgentUpdate.Chunk(kind, messageId, "text", text.text() == null ? "" : text.text());
            case AcpSchema.ImageContent ignored -> new AgentUpdate.Chunk(kind, messageId, "image", null);
            case AcpSchema.AudioContent ignored -> new AgentUpdate.Chunk(kind, messageId, "audio", null);
            case AcpSchema.ResourceLink ignored -> new AgentUpdate.Chunk(kind, messageId, "resource_link", null);
            case AcpSchema.Resource ignored -> new AgentUpdate.Chunk(kind, messageId, "resource", null);
            case AcpSchema.UnknownContentBlock unknown ->
                    new AgentUpdate.Chunk(kind, messageId, typeName(unknown.type()), null);
            case null -> new AgentUpdate.Chunk(kind, messageId, "unknown", null);
            default -> new AgentUpdate.Chunk(kind, messageId, "unknown", null);
        };
    }

    private List<Map<String, Object>> content(List<AcpSchema.ToolCallContent> content) {
        if (content == null) {
            return null;
        }
        List<Map<String, Object>> result = new ArrayList<>(content.size());
        content.forEach(item -> result.add(AdapterValues.object(mapper.convertValue(item, JSON_OBJECT))));
        return result;
    }

    /** A JSON value as plain maps and lists: the SDK may hand over its own node types. */
    private Object json(Object value) {
        if (value == null || value instanceof String || value instanceof Number || value instanceof Boolean
                || value instanceof Map<?, ?> || value instanceof List<?>) {
            return value;
        }
        return mapper.convertValue(value, Object.class);
    }

    private static List<ToolCallLocation> locations(List<AcpSchema.ToolCallLocation> locations) {
        if (locations == null) {
            return null;
        }
        return locations.stream().filter(location -> location.path() != null).map(location -> {
            ToolCallLocation translated = new ToolCallLocation();
            translated.setPath(AdapterValues.text(location.path()));
            translated.setLine(location.line());
            return translated;
        }).toList();
    }

    /** The SDK reads an unknown wire kind as {@code OTHER}, which maps to {@code other} (ux.md R6). */
    private static ToolKind kind(AcpSchema.ToolKind kind) {
        if (kind == null) {
            return null;
        }
        return switch (kind) {
            case READ -> ToolKind.READ;
            case EDIT -> ToolKind.EDIT;
            case DELETE -> ToolKind.DELETE;
            case MOVE -> ToolKind.MOVE;
            case SEARCH -> ToolKind.SEARCH;
            case EXECUTE -> ToolKind.EXECUTE;
            case THINK -> ToolKind.THINK;
            case FETCH -> ToolKind.FETCH;
            case SWITCH_MODE -> ToolKind.SWITCH_MODE;
            case OTHER -> ToolKind.OTHER;
        };
    }

    /** An unknown wire status is dropped, as the contract says (ToolCallStatus). */
    private static ToolCallStatus status(AcpSchema.ToolCallStatus status) {
        if (status == null || status.value() == null) {
            return null;
        }
        for (ToolCallStatus known : ToolCallStatus.values()) {
            if (known.getValue().equals(status.value())) {
                return known;
            }
        }
        return null;
    }

    private static Optional<PermissionOptionKind> optionKind(AcpSchema.PermissionOptionKind kind) {
        if (kind == null || kind.value() == null) {
            return Optional.empty();
        }
        for (PermissionOptionKind known : PermissionOptionKind.values()) {
            if (known.getValue().equals(kind.value())) {
                return Optional.of(known);
            }
        }
        return Optional.empty();
    }

    private static PlanEntry planEntry(AcpSchema.PlanEntry entry) {
        PlanEntry translated = new PlanEntry();
        translated.setContent(entry.content() == null ? "" : AdapterValues.text(entry.content()));
        translated.setPriority(entry.priority() == null || entry.priority().value() == null ? ""
                : AdapterValues.text(entry.priority().value()));
        translated.setStatus(entry.status() == null || entry.status().value() == null ? ""
                : AdapterValues.text(entry.status().value()));
        return translated;
    }

    private static AvailableCommand command(AcpSchema.AvailableCommand command) {
        AvailableCommand translated = new AvailableCommand();
        translated.setName(command.name() == null ? "" : AdapterValues.text(command.name()));
        translated.setDescription(command.description() == null ? "" : AdapterValues.text(command.description()));
        return translated;
    }

    private static SessionInfoData sessionInfo(AcpSchema.SessionInfoUpdate info) {
        SessionInfoData data = new SessionInfoData();
        data.setTitle(AdapterValues.text(info.title()));
        data.setUpdatedAt(AdapterValues.text(info.updatedAt()));
        return data;
    }

    private static UsageData usage(AcpSchema.UsageUpdate usage) {
        UsageData data = new UsageData();
        data.setUsed(usage.used() == null ? 0L : usage.used());
        data.setSize(usage.size() == null ? 0L : usage.size());
        if (usage.cost() != null && usage.cost().amount() != null && usage.cost().currency() != null) {
            Cost cost = new Cost();
            cost.setAmount(usage.cost().amount());
            cost.setCurrency(AdapterValues.text(usage.cost().currency()));
            data.setCost(cost);
        }
        return data;
    }

    private static String typeName(String type) {
        return type == null || type.isEmpty() ? "unknown" : AdapterValues.text(type);
    }
}
