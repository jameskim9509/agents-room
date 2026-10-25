package agentsroom.runtime.gateway;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import tools.jackson.databind.JsonNode;

/**
 * The shape {@code core/asyncapi.yaml} gives each agents-room command, checked on the JSON tree before
 * the frame is bound to its generated class (architecture.md, Renderer gateway). Binding alone enforces
 * none of {@code required}, the field types and {@code additionalProperties: false}, and it cannot tell
 * a missing nullable field from a {@code null} one. Every command field is a string, a nullable string
 * or an object.
 *
 * <p>{@code ContractMessageCodecTests} keeps this table equal to the contract: its commands and fields
 * must be those of the generated classes, and on every valid client fixture and on variants of each
 * (fields removed, nulled, mistyped, added) the codec must agree with the contract's own validator
 * ({@code scripts/check-contract-fixtures.mjs}, ajv).
 */
final class CommandShapes {

    static final String TYPE = "type";

    /** How much of a field name a message quotes. Names come from the renderer. */
    private static final int MAX_QUOTED_NAME_LENGTH = 64;

    enum Kind {
        STRING, NULLABLE_STRING, OBJECT
    }

    /** @param shape the nested object's shape, for {@link Kind#OBJECT} only */
    record Field(String name, boolean required, Kind kind, Shape shape) {
    }

    /** @param fields by name, in contract order */
    record Shape(Map<String, Field> fields) {

        Shape {
            fields = Collections.unmodifiableMap(new LinkedHashMap<>(fields));
        }
    }

    private static final Shape AGENT_CONFIG_INPUT = shape(
            optional("agentId"), required("projectId"), required("name"), required("harnessId"),
            required("providerId"), nullable("modelId"), required("authMethodId"));

    /** Every agents-room command by {@code type}. Legacy (pixel-agents) commands are not served. */
    static final Map<String, Shape> COMMANDS = Map.of(
            "openProject", shape(required("path")),
            "relocateProject", shape(required("projectId"), required("path")),
            "saveAgentConfig", shape(object("config", AGENT_CONFIG_INPUT)),
            "checkConnection", shape(required("agentId")),
            "startAgent", shape(required("agentId")),
            "stopAgent", shape(required("agentId")),
            "sendPrompt", shape(required("agentId"), required("text")),
            "cancelRun", shape(required("agentId"), required("runId")),
            "respondPermission", shape(required("permissionId"), required("optionId")));

    private CommandShapes() {
    }

    /**
     * Why {@code frame} is not a valid command of its type, or empty when it is one.
     *
     * @param type the frame's {@code type}, already known to be a string
     */
    static Optional<String> problem(String type, JsonNode frame) {
        Shape shape = COMMANDS.get(type);
        if (shape == null) {
            return Optional.of("Not an agents-room command");
        }
        return problem(type, "", shape, frame);
    }

    private static Optional<String> problem(String command, String prefix, Shape shape, JsonNode object) {
        for (String name : object.propertyNames()) {
            boolean discriminator = prefix.isEmpty() && name.equals(TYPE);
            if (!discriminator && !shape.fields().containsKey(name)) {
                return Optional.of(quote(prefix, name) + " is not a field of " + command);
            }
        }
        for (Field field : shape.fields().values()) {
            JsonNode value = object.get(field.name());
            String quoted = quote(prefix, field.name());
            if (value == null) {
                if (field.required()) {
                    return Optional.of(quoted + " is missing");
                }
                continue;
            }
            switch (field.kind()) {
                case STRING -> {
                    if (!value.isString()) {
                        return Optional.of(quoted + " must be a string");
                    }
                }
                case NULLABLE_STRING -> {
                    if (!value.isString() && !value.isNull()) {
                        return Optional.of(quoted + " must be a string or null");
                    }
                }
                case OBJECT -> {
                    if (!value.isObject()) {
                        return Optional.of(quoted + " must be an object");
                    }
                    Optional<String> nested = problem(command, prefix + field.name() + ".", field.shape(), value);
                    if (nested.isPresent()) {
                        return nested;
                    }
                }
            }
        }
        return Optional.empty();
    }

    private static String quote(String prefix, String name) {
        String quoted = prefix + name;
        if (quoted.length() > MAX_QUOTED_NAME_LENGTH) {
            quoted = quoted.substring(0, MAX_QUOTED_NAME_LENGTH) + "...";
        }
        return "'" + quoted + "'";
    }

    private static Shape shape(Field... fields) {
        return new Shape(Arrays.stream(fields).collect(
                Collectors.toMap(Field::name, Function.identity(), (a, b) -> a, LinkedHashMap::new)));
    }

    private static Field required(String name) {
        return new Field(name, true, Kind.STRING, null);
    }

    private static Field optional(String name) {
        return new Field(name, false, Kind.STRING, null);
    }

    private static Field nullable(String name) {
        return new Field(name, true, Kind.NULLABLE_STRING, null);
    }

    private static Field object(String name, Shape shape) {
        return new Field(name, true, Kind.OBJECT, shape);
    }
}
