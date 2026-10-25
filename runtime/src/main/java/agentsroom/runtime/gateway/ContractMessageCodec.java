package agentsroom.runtime.gateway;

import agentsroom.contract.ClientMessage;
import agentsroom.contract.ServerMessage;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * The wire format over the Java bindings generated from {@code core/asyncapi.yaml}: {@link ClientMessage}
 * in, {@link ServerMessage} out, both discriminated by {@code type}. The generated unions hold the
 * agents-room frames only; the inherited pixel-agents ({@code x-legacy}) frames are left out, so a
 * legacy command is rejected like an unknown one.
 *
 * <p>Decoding checks the frame against its command's {@link CommandShapes shape} before binding it,
 * because the generated classes enforce no part of the schema (architecture.md, Renderer gateway).
 */
public final class ContractMessageCodec implements RendererMessageCodec {

    private static final Logger log = LoggerFactory.getLogger(ContractMessageCodec.class);

    /** The field {@code saveAgentConfig} carries its ids in. */
    private static final String CONFIG = "config";

    /** Its own mapper, independent of Boot's auto-configured one. */
    private final JsonMapper mapper = JsonMapper.builder()
            // Fields go out in contract order, `type` first, like the fixtures.
            .disable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
            // JSON.parse keeps the last of two equal keys, and the contract validator sees only that one;
            // a renderer never sends a key twice.
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build();

    @Override
    public ClientMessage decode(String text) throws MalformedFrameException {
        JsonNode frame;
        try {
            frame = mapper.readTree(text);
        } catch (JacksonException e) {
            throw new MalformedFrameException("Frame is not JSON", CommandEcho.of(CommandEcho.UNKNOWN_COMMAND));
        }
        if (frame == null || !frame.isObject()) {
            throw new MalformedFrameException("Frame is not a JSON object",
                    CommandEcho.of(CommandEcho.UNKNOWN_COMMAND));
        }
        CommandEcho echo = echo(frame);
        Optional<String> type = string(frame, CommandShapes.TYPE);
        if (type.isEmpty()) {
            throw new MalformedFrameException("Frame has no string 'type'", echo);
        }
        Optional<String> problem = CommandShapes.problem(type.get(), frame);
        if (problem.isPresent()) {
            throw new MalformedFrameException(problem.get(), echo);
        }
        try {
            return mapper.treeToValue(frame, ClientMessage.class);
        } catch (JacksonException e) {
            // The shape check passed, so CommandShapes and the generated classes disagree. The exception
            // message can quote the frame, so only its type is logged.
            log.error("A {} command passed its shape check but did not bind ({})", type.get(),
                    e.getClass().getSimpleName());
            throw new MalformedFrameException("Frame does not bind to its command", echo);
        }
    }

    @Override
    public String encode(ServerMessage frame) {
        return mapper.writeValueAsString(frame);
    }

    /** The frame's type and the ids it carried, at the top level or in {@code config}. */
    private static CommandEcho echo(JsonNode frame) {
        return new CommandEcho(
                string(frame, CommandShapes.TYPE).orElse(CommandEcho.UNKNOWN_COMMAND),
                id(frame, "agentId"),
                id(frame, "projectId"),
                id(frame, "permissionId"));
    }

    private static String id(JsonNode frame, String name) {
        JsonNode config = frame.get(CONFIG);
        return string(frame, name)
                .or(() -> config != null && config.isObject() ? string(config, name) : Optional.empty())
                .orElse(null);
    }

    private static Optional<String> string(JsonNode object, String name) {
        JsonNode value = object.get(name);
        return value != null && value.isString() ? Optional.of(value.stringValue()) : Optional.empty();
    }
}
