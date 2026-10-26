package agentsroom.runtime.gateway;

import agentsroom.contract.ClientMessage;
import agentsroom.contract.ServerMessage;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.core.StreamReadConstraints;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.exc.StreamConstraintsException;
import tools.jackson.core.json.JsonFactory;
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
 * <p>Decoding reads a frame twice. A streaming pass finds what a rejection echoes (the {@code type} and
 * the ids, ADR 0005) and whether the frame is JSON at all, without building a tree and without parser
 * limits, so every well-formed frame gets its echo. Then the tree is read with Jackson's default limits
 * and a repeated key is refused, and the frame is checked against its command's {@link CommandShapes
 * shape} before it is bound, because the generated classes enforce no part of the schema
 * (architecture.md, Renderer gateway).
 */
public final class ContractMessageCodec implements RendererMessageCodec {

    private static final Logger log = LoggerFactory.getLogger(ContractMessageCodec.class);

    /** The field {@code saveAgentConfig} carries its ids in. */
    private static final String CONFIG = "config";

    private static final String AGENT_ID = "agentId";
    private static final String PROJECT_ID = "projectId";
    private static final String PERMISSION_ID = "permissionId";
    private static final Set<String> IDS = Set.of(AGENT_ID, PROJECT_ID, PERMISSION_ID);

    private static final StreamReadConstraints TREE_LIMITS = StreamReadConstraints.defaults();

    private static final String NOT_JSON = "Frame is not JSON";

    private static final String BEYOND_LIMITS = "Frame exceeds the runtime's JSON limits (nesting depth "
            + TREE_LIMITS.getMaxNestingDepth() + ", number length " + TREE_LIMITS.getMaxNumberLength()
            + ", name length " + TREE_LIMITS.getMaxNameLength() + ")";

    /**
     * The tree and the outbound frames. Its own mapper, independent of Boot's auto-configured one.
     */
    private final JsonMapper mapper = JsonMapper.builder()
            // Fields go out in contract order, `type` first, like the fixtures.
            .disable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
            // JSON.parse keeps the last of two equal keys, and the contract validator sees only that one;
            // a renderer never sends a key twice.
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build();

    /**
     * The echo pass: no limit on nesting, number or name length, which bound the tree only. It builds
     * nothing, so its memory grows with the nesting depth alone, and the inbound frame limit
     * ({@link RendererGatewayConfiguration#MAX_INBOUND_FRAME_BYTES}) bounds that.
     */
    private final JsonMapper scanner = JsonMapper.builder(JsonFactory.builder()
                    .streamReadConstraints(StreamReadConstraints.builder()
                            .maxNestingDepth(Integer.MAX_VALUE)
                            .maxNumberLength(Integer.MAX_VALUE)
                            .maxNameLength(Integer.MAX_VALUE)
                            .build())
                    .build())
            .build();

    @Override
    public ClientMessage decode(String text) throws MalformedFrameException {
        CommandEcho echo = scan(text);
        JsonNode frame = tree(text, echo);
        JsonNode type = frame.get(CommandShapes.TYPE);
        if (type == null || !type.isString()) {
            throw new MalformedFrameException("Frame has no string 'type'", echo);
        }
        Optional<String> problem = CommandShapes.problem(type.stringValue(), frame);
        if (problem.isPresent()) {
            throw new MalformedFrameException(problem.get(), echo);
        }
        try {
            return mapper.treeToValue(frame, ClientMessage.class);
        } catch (JacksonException e) {
            // The shape check passed, so CommandShapes and the generated classes disagree. The exception
            // message can quote the frame, so only its type is logged.
            log.error("A {} command passed its shape check but did not bind ({})", type.stringValue(),
                    e.getClass().getSimpleName());
            throw new MalformedFrameException("Frame does not bind to its command", echo);
        }
    }

    @Override
    public String encode(ServerMessage frame) {
        return mapper.writeValueAsString(frame);
    }

    /**
     * The streaming pass: the frame's {@code type} and the ids it carried, at the top level or in
     * {@code config} (a top-level id wins). Like {@code JSON.parse}, the last of two equal keys counts,
     * and a value that is not a string echoes nothing.
     *
     * @throws MalformedFrameException when the frame is not one JSON object
     */
    private CommandEcho scan(String text) throws MalformedFrameException {
        Map<String, String> top = new HashMap<>();
        Map<String, String> inConfig = new HashMap<>();
        try (JsonParser parser = scanner.createParser(text)) {
            JsonToken root = parser.nextToken();
            if (root == null) {
                throw notJson();
            }
            if (root != JsonToken.START_OBJECT) {
                parser.skipChildren();
                requireEnd(parser);
                throw new MalformedFrameException("Frame is not a JSON object",
                        CommandEcho.of(CommandEcho.UNKNOWN_COMMAND));
            }
            while (parser.nextToken() == JsonToken.PROPERTY_NAME) {
                String name = parser.currentName();
                JsonToken value = parser.nextToken();
                if (name.equals(CONFIG)) {
                    inConfig.clear();
                    if (value == JsonToken.START_OBJECT) {
                        while (parser.nextToken() == JsonToken.PROPERTY_NAME) {
                            String inner = parser.currentName();
                            parser.nextToken();
                            record(inConfig, inner, IDS.contains(inner), parser);
                        }
                        continue;
                    }
                }
                record(top, name, name.equals(CommandShapes.TYPE) || IDS.contains(name), parser);
            }
            requireEnd(parser);
        } catch (JacksonException e) {
            throw notJson();
        }
        return new CommandEcho(
                top.getOrDefault(CommandShapes.TYPE, CommandEcho.UNKNOWN_COMMAND),
                id(top, inConfig, AGENT_ID),
                id(top, inConfig, PROJECT_ID),
                id(top, inConfig, PERMISSION_ID));
    }

    /**
     * The tree, read with Jackson's default limits. The scan has already shown the text to be one JSON
     * object, so a failure here is a parser limit or a repeated key.
     */
    private JsonNode tree(String text, CommandEcho echo) throws MalformedFrameException {
        JsonParser parser = mapper.createParser(text);
        try {
            return mapper.readTree(parser);
        } catch (StreamConstraintsException e) {
            throw new MalformedFrameException(BEYOND_LIMITS, echo);
        } catch (JacksonException e) {
            // The parser stops on the second of the two keys, so its current name is the repeated one.
            String name = parser.streamReadContext().currentName();
            throw new MalformedFrameException(
                    name == null ? "Frame repeats a key" : CommandShapes.quote(name) + " appears twice", echo);
        } finally {
            parser.close();
        }
    }

    /** Records {@code name}'s value when it is echoed, and skips the value either way. */
    private static void record(Map<String, String> into, String name, boolean echoed, JsonParser parser) {
        if (echoed) {
            if (parser.currentToken() == JsonToken.VALUE_STRING) {
                into.put(name, parser.getString());
            } else {
                into.remove(name);
            }
        }
        parser.skipChildren();
    }

    private static String id(Map<String, String> top, Map<String, String> inConfig, String name) {
        return top.containsKey(name) ? top.get(name) : inConfig.get(name);
    }

    /** {@code JSON.parse} accepts one value and whitespace, nothing after it. */
    private static void requireEnd(JsonParser parser) throws MalformedFrameException {
        if (parser.nextToken() != null) {
            throw notJson();
        }
    }

    private static MalformedFrameException notJson() {
        return new MalformedFrameException(NOT_JSON, CommandEcho.of(CommandEcho.UNKNOWN_COMMAND));
    }
}
