package agentsroom.runtime.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import agentsroom.contract.AgentConfigInput;
import agentsroom.contract.ClientMessage;
import agentsroom.contract.SaveAgentConfig;
import agentsroom.contract.SendPrompt;
import agentsroom.contract.ServerMessage;
import agentsroom.runtime.testsupport.ContractSchema;
import agentsroom.runtime.testsupport.ContractSchema.Fixture;
import agentsroom.runtime.testsupport.ContractSchema.Verdict;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * The codec against {@code core/asyncapi.yaml}: every fixture in both directions, and the command check
 * against the contract's own validator.
 */
class ContractMessageCodecTests {

    private final ContractMessageCodec codec = new ContractMessageCodec();
    private final JsonMapper json = JsonMapper.builder().build();

    @Test
    void decodesEveryValidClientFixtureWithoutLosingAField() throws MalformedFrameException {
        List<Fixture> fixtures = ContractSchema.fixtures("valid", "client");
        assertThat(fixtures).hasSize(CommandShapes.COMMANDS.size());

        for (Fixture fixture : fixtures) {
            JsonNode expected = json.readTree(fixture.text());

            ClientMessage command = codec.decode(fixture.text());

            JsonNode bound = json.valueToTree(command);
            assertThat(command.getType()).as(fixture.name()).isEqualTo(expected.get("type").asString());
            assertThat(bound).as(fixture.name()).isEqualTo(expected);
        }
    }

    @Test
    void rejectsEveryInvalidClientFixture() {
        for (Fixture fixture : ContractSchema.fixtures("invalid", "client")) {
            String message = json.readTree(fixture.text()).get("message").toString();

            assertThatThrownBy(() -> codec.decode(message)).as(fixture.name())
                    .isInstanceOf(MalformedFrameException.class);
        }
    }

    @Test
    void encodesEveryValidServerFixtureExactlyAsWritten() {
        for (Fixture fixture : ContractSchema.fixtures("valid", "server")) {
            ServerMessage frame = json.readValue(fixture.text(), ServerMessage.class);

            String encoded = codec.encode(frame);

            assertThat(json.readTree(encoded)).as(fixture.name()).isEqualTo(json.readTree(fixture.text()));
            assertThat(encoded).as("type first").startsWith("{\"type\":\"" + frame.getType() + "\"");
        }
    }

    /**
     * Differential check: for each valid client fixture and its variants (every field removed, nulled
     * and mistyped, an unknown field added, at every level), plus legacy and malformed frames, the
     * codec accepts exactly what the contract validator accepts as an agents-room command.
     */
    @Test
    void acceptsExactlyTheCommandsTheContractValidatorAccepts() {
        List<String> candidates = new ArrayList<>();
        for (Fixture fixture : ContractSchema.fixtures("valid", "client")) {
            ObjectNode command = (ObjectNode) json.readTree(fixture.text());
            candidates.add(command.toString());
            addVariants(command, List.of(), candidates);
        }
        for (Fixture fixture : ContractSchema.fixtures("invalid", "client")) {
            candidates.add(json.readTree(fixture.text()).get("message").toString());
        }
        candidates.addAll(List.of(
                // Inherited pixel-agents commands are valid ClientMessages, but the runtime serves none.
                "{\"type\":\"webviewReady\"}",
                "{\"type\":\"launchAgent\",\"folderPath\":\"C:\\\\work\"}",
                "{\"type\":\"focusAgent\",\"id\":1}",
                // The fixture's saveAgentConfig creates an agent; these name one.
                saveAgentConfig(config -> config.put("agentId", "0b9a8c7d-6e5f-4a3b-8c2d-1e0f9a8b7c6d")),
                saveAgentConfig(config -> config.putNull("agentId")),
                saveAgentConfig(config -> config.put("modelId", "gpt-5-codex")),
                "{}", "[]", "\"openProject\"", "42", "null", "true",
                "{\"type\":\"\"}", "{\"type\":\"authenticate\",\"agentId\":\"a\"}"));

        List<Verdict> verdicts = ContractSchema.validateClientMessages(candidates);

        List<String> disagreements = new ArrayList<>();
        int accepted = 0;
        for (int i = 0; i < candidates.size(); i++) {
            boolean decoded = decodes(candidates.get(i));
            accepted += decoded ? 1 : 0;
            if (decoded != verdicts.get(i).isAgentsRoomMessage()) {
                disagreements.add((decoded ? "codec accepts, contract rejects " : "codec rejects, contract accepts ")
                        + candidates.get(i) + " " + verdicts.get(i).errors());
            }
        }
        assertThat(disagreements).isEmpty();
        assertThat(candidates).as("enough variants to mean something").hasSizeGreaterThan(150);
        assertThat(accepted).as("some variants are valid").isGreaterThan(CommandShapes.COMMANDS.size());
    }

    /** The shapes name exactly the generated commands and fields, so a yaml change cannot slip past them. */
    @Test
    void theShapesNameExactlyTheGeneratedCommandsAndFields() {
        Map<String, Class<?>> generated = Arrays.stream(ClientMessage.class.getAnnotation(JsonSubTypes.class).value())
                .collect(Collectors.toMap(JsonSubTypes.Type::name, JsonSubTypes.Type::value));
        assertThat(CommandShapes.COMMANDS.keySet()).containsExactlyInAnyOrderElementsOf(generated.keySet());

        generated.forEach((type, commandClass) -> {
            Set<String> fields = jsonProperties(commandClass);
            fields.remove(CommandShapes.TYPE);
            assertThat(CommandShapes.COMMANDS.get(type).fields().keySet()).as(type)
                    .containsExactlyInAnyOrderElementsOf(fields);
        });
        CommandShapes.Field config = CommandShapes.COMMANDS.get("saveAgentConfig").fields().get("config");
        assertThat(config.shape().fields().keySet()).containsExactlyInAnyOrderElementsOf(
                jsonProperties(AgentConfigInput.class));
    }

    @Test
    void rejectsWhatTheSchemaCannotSeeInParsedJson() {
        String path = "\"path\":\"C:\\\\work\"";

        assertThatThrownBy(() -> codec.decode("{\"type\":\"openProject\"," + path + "," + path + "}"))
                .as("a duplicate key").isInstanceOf(MalformedFrameException.class);
        assertThatThrownBy(() -> codec.decode("{\"type\":\"openProject\"," + path + "} {}"))
                .as("trailing content").isInstanceOf(MalformedFrameException.class);
        assertThatThrownBy(() -> codec.decode("")).as("an empty frame").isInstanceOf(MalformedFrameException.class);
    }

    @Test
    void aRejectionEchoesTheTypeAndTheIdsTheFrameCarried() {
        assertThat(echoOf("not json")).isEqualTo(CommandEcho.of(CommandEcho.UNKNOWN_COMMAND));
        assertThat(echoOf("{\"agentId\":\"a1\"}")).isEqualTo(CommandEcho.agent(CommandEcho.UNKNOWN_COMMAND, "a1"));
        assertThat(echoOf("{\"type\":\"sendPrompt\",\"agentId\":\"a1\"}")).isEqualTo(CommandEcho.agent("sendPrompt", "a1"));
        assertThat(echoOf("{\"type\":\"respondPermission\",\"permissionId\":\"p1\",\"optionId\":7}"))
                .isEqualTo(CommandEcho.permission("respondPermission", "p1"));
        assertThat(echoOf("{\"type\":\"saveAgentConfig\",\"config\":{\"agentId\":\"a1\",\"projectId\":\"p1\"}}"))
                .isEqualTo(new CommandEcho("saveAgentConfig", "a1", "p1", null));
        assertThat(echoOf("{\"type\":\"bogus\",\"agentId\":7}")).isEqualTo(CommandEcho.of("bogus"));
    }

    @Test
    void namesTheProblemWithoutQuotingTheValue() {
        assertThatThrownBy(() -> codec.decode("{\"type\":\"sendPrompt\",\"agentId\":\"a\",\"text\":42}"))
                .hasMessage("'text' must be a string");
        assertThatThrownBy(() -> codec.decode("{\"type\":\"sendPrompt\",\"agentId\":\"a\"}"))
                .hasMessage("'text' is missing");
        assertThatThrownBy(() -> codec.decode("{\"type\":\"startAgent\",\"agentId\":\"a\",\"force\":true}"))
                .hasMessage("'force' is not a field of startAgent");
        assertThatThrownBy(() -> codec.decode("{\"type\":\"saveAgentConfig\",\"config\":{}}"))
                .hasMessage("'config.projectId' is missing");
        assertThatThrownBy(() -> codec.decode("{\"type\":\"webviewReady\"}")).hasMessage("Not an agents-room command");
    }

    @Test
    void bindsNullableAndOptionalFields() throws MalformedFrameException {
        SaveAgentConfig save = (SaveAgentConfig) codec.decode(saveAgentConfig(config -> config.put("modelId", "m1")));
        assertThat(save.getConfig().getModelId()).isEqualTo("m1");
        assertThat(save.getConfig().getAgentId()).isNull();

        SendPrompt prompt = (SendPrompt) codec.decode("{\"type\":\"sendPrompt\",\"agentId\":\"a\",\"text\":\"\"}");
        assertThat(prompt.getText()).isEmpty();
    }

    private boolean decodes(String text) {
        try {
            codec.decode(text);
            return true;
        } catch (MalformedFrameException e) {
            return false;
        }
    }

    private CommandEcho echoOf(String text) {
        try {
            codec.decode(text);
        } catch (MalformedFrameException e) {
            return e.echo();
        }
        throw new AssertionError("decoded: " + text);
    }

    /**
     * Variants of {@code root}, each one edit of the object at {@code path}: every field removed, nulled
     * and set to each other JSON type, and one unknown field added. Recurses into nested objects.
     */
    private static void addVariants(ObjectNode root, List<String> path, List<String> out) {
        ObjectNode object = at(root, path);
        for (String name : List.copyOf(object.propertyNames())) {
            out.add(edited(root, path, copy -> copy.remove(name)));
            out.add(edited(root, path, copy -> copy.putNull(name)));
            out.add(edited(root, path, copy -> copy.put(name, 42)));
            out.add(edited(root, path, copy -> copy.put(name, true)));
            out.add(edited(root, path, copy -> copy.put(name, "")));
            out.add(edited(root, path, copy -> copy.putObject(name)));
            out.add(edited(root, path, copy -> copy.putArray(name).add("x")));
            if (object.get(name).isObject()) {
                List<String> nested = new ArrayList<>(path);
                nested.add(name);
                addVariants(root, nested, out);
            }
        }
        out.add(edited(root, path, copy -> copy.put("extra", "x")));
    }

    /** {@code root} as text after {@code edit} changed a copy of its object at {@code path}. */
    private static String edited(ObjectNode root, List<String> path, Consumer<ObjectNode> edit) {
        ObjectNode copy = root.deepCopy();
        edit.accept(at(copy, path));
        return copy.toString();
    }

    private static ObjectNode at(ObjectNode root, List<String> path) {
        ObjectNode object = root;
        for (String name : path) {
            object = (ObjectNode) object.get(name);
        }
        return object;
    }

    private String saveAgentConfig(Consumer<ObjectNode> edit) {
        ObjectNode command = (ObjectNode) json.readTree(ContractSchema.fixtures("valid", "client").stream()
                .filter(fixture -> fixture.name().equals("saveAgentConfig.json"))
                .findFirst()
                .orElseThrow()
                .text());
        edit.accept((ObjectNode) command.get("config"));
        return command.toString();
    }

    /** The JSON names of a generated class's fields. */
    private static Set<String> jsonProperties(Class<?> type) {
        return Arrays.stream(type.getDeclaredFields())
                .map(field -> field.getAnnotation(JsonProperty.class))
                .filter(Objects::nonNull)
                .map(JsonProperty::value)
                .collect(Collectors.toCollection(HashSet::new));
    }
}
