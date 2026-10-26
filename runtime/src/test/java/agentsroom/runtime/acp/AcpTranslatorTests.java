package agentsroom.runtime.acp;

import static org.assertj.core.api.Assertions.assertThat;

import agentsroom.contract.AuthStatus;
import agentsroom.contract.PermissionOptionKind;
import agentsroom.contract.ToolCallStatus;
import agentsroom.contract.ToolKind;
import com.agentclientprotocol.sdk.json.AcpJsonMapper;
import com.agentclientprotocol.sdk.spec.AcpSchema;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** SDK records → the port's values, as the SDK reads real ACP JSON. */
class AcpTranslatorTests {

    private final AcpJsonMapper mapper = AcpJsonMapper.createDefault();
    private final AcpTranslator translator = new AcpTranslator(mapper);

    @Test
    void theAuthStatusKeepsKindAndLabelAndDropsTheAccount() {
        Optional<AuthStatus> status = AcpTranslator.authStatus(Map.of("authStatus", Map.of("kind", "account",
                "label", "ChatGPT Plus", "account", Map.of("email", "someone@example.com", "plan", "plus"))));

        assertThat(status).hasValueSatisfying(value -> {
            assertThat(value.getKind()).isEqualTo("account");
            assertThat(value.getLabel()).isEqualTo("ChatGPT Plus");
        });
        assertThat(AcpTranslator.authStatus(Map.of("other", 1))).isEmpty();
        assertThat(AcpTranslator.authStatus("text")).isEmpty();
    }

    @Test
    void anUnknownKindIsOtherAndAnUnknownStatusIsLeftOut() throws Exception {
        AgentUpdate update = translator.update(sessionUpdate("""
                {"sessionUpdate":"tool_call","toolCallId":"c1","title":"Frobnicate","kind":"frobnicate",
                 "status":"paused","rawInput":{"path":"a.txt"},"_meta":{"x":"y"}}"""));

        assertThat(update).isInstanceOfSatisfying(AgentUpdate.ToolCallReport.class, report -> {
            assertThat(report.initial()).isTrue();
            assertThat(report.kind()).isEqualTo(ToolKind.OTHER);
            assertThat(report.status()).isNull();
            assertThat(report.rawInput()).isEqualTo(Map.of("path", "a.txt"));
            assertThat(report.meta()).isEqualTo(Map.of("x", "y"));
        });
    }

    @Test
    void anUpdateCarriesOnlyWhatItReports() throws Exception {
        AgentUpdate update = translator.update(sessionUpdate("""
                {"sessionUpdate":"tool_call_update","toolCallId":"c1","status":"completed"}"""));

        assertThat(update).isInstanceOfSatisfying(AgentUpdate.ToolCallReport.class, report -> {
            assertThat(report.initial()).isFalse();
            assertThat(report.status()).isEqualTo(ToolCallStatus.COMPLETED);
            assertThat(report.title()).isNull();
            assertThat(report.kind()).isNull();
            assertThat(report.locations()).isNull();
            assertThat(report.content()).isNull();
        });
    }

    @Test
    void anUpdateTheSdkDoesNotModelIsUnknownWithItsTypeOnly() throws Exception {
        assertThat(translator.update(sessionUpdate("""
                {"sessionUpdate":"subagent_spawned","subagentId":"s1"}""")))
                .isEqualTo(new AgentUpdate.Unknown("subagent_spawned"));
    }

    @Test
    void chunksKeepTheirMessageIdAndOnlyTextHasText() throws Exception {
        AgentUpdate text = translator.update(sessionUpdate("""
                {"sessionUpdate":"agent_message_chunk","messageId":"m1","content":{"type":"text","text":"hi"}}"""));
        AgentUpdate image = translator.update(sessionUpdate("""
                {"sessionUpdate":"agent_thought_chunk",
                 "content":{"type":"image","data":"aGk=","mimeType":"image/png"}}"""));

        assertThat(text).isInstanceOfSatisfying(AgentUpdate.Chunk.class, chunk -> {
            assertThat(chunk.kind()).isEqualTo(AgentUpdate.ChunkKind.AGENT);
            assertThat(chunk.data().getMessageId()).isEqualTo("m1");
            assertThat(chunk.data().getText()).isEqualTo("hi");
        });
        assertThat(image).isInstanceOfSatisfying(AgentUpdate.Chunk.class, chunk -> {
            assertThat(chunk.kind()).isEqualTo(AgentUpdate.ChunkKind.THOUGHT);
            assertThat(chunk.data().getContentType()).isEqualTo("image");
            assertThat(chunk.data().getText()).isNull();
        });
    }

    @Test
    void permissionOptionsKeepTheirIdsAndDropUnknownKinds() throws Exception {
        AcpSchema.RequestPermissionRequest request = mapper.readValue("""
                {"sessionId":"s1","toolCall":{"toolCallId":"c1","title":"Run rm"},
                 "options":[{"optionId":"allow_once","name":"Yes","kind":"allow_once"},
                            {"optionId":"maybe","name":"Maybe","kind":"ask_later"},
                            {"optionId":"decline","name":"No","kind":"reject_once"}]}""",
                AcpSchema.RequestPermissionRequest.class);

        PermissionAsk ask = translator.permission(request);

        assertThat(ask.sessionId()).isEqualTo("s1");
        assertThat(ask.toolCall().title()).isEqualTo("Run rm");
        assertThat(ask.options()).extracting(option -> option.getOptionId()).containsExactly("allow_once", "decline");
        assertThat(ask.options()).extracting(option -> option.getKind())
                .containsExactly(PermissionOptionKind.ALLOW_ONCE, PermissionOptionKind.REJECT_ONCE);
        assertThat(ask.hasOption("maybe")).isFalse();
    }

    @Test
    void sessionValuesBecomePlainMasked() throws Exception {
        AcpSchema.NewSessionResponse response = mapper.readValue("""
                {"sessionId":"s1","modes":{"currentModeId":"agent","availableModes":[{"id":"agent","name":"Auto"}]},
                 "configOptions":[{"type":"select","id":"model","name":"Model","category":"model",
                                   "currentValue":"gpt","options":[{"value":"gpt","name":"GPT"}]}]}""",
                AcpSchema.NewSessionResponse.class);

        Map<String, Object> modes = translator.modes(response.modes());
        List<Map<String, Object>> options = translator.configOptions(response.configOptions());

        assertThat(modes).containsEntry("currentModeId", "agent");
        assertThat(options).singleElement().satisfies(option -> {
            assertThat(option).containsEntry("category", "model").containsEntry("currentValue", "gpt");
        });
    }

    private AcpSchema.SessionUpdate sessionUpdate(String json) throws Exception {
        return mapper.readValue("{\"sessionId\":\"s1\",\"update\":" + json + "}", AcpSchema.SessionNotification.class)
                .update();
    }
}
