package agentsroom.runtime.permission;

import static org.assertj.core.api.Assertions.assertThat;

import agentsroom.runtime.acp.PermissionAnswer;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;

/** The Permission broker answers each request exactly once (ADR 0005 decision 7). */
class PermissionBrokerTests {

    private final PermissionBroker broker = new PermissionBroker();

    @Test
    void answersARequestOnceAndThenForgetsIt() {
        CompletableFuture<PermissionAnswer> answer = new CompletableFuture<>();
        String permissionId = broker.register("agent-1", answer);

        assertThat(broker.find(permissionId)).hasValueSatisfying(pending -> {
            assertThat(pending.agentId()).isEqualTo("agent-1");
            assertThat(pending.answer()).isSameAs(answer);
        });
        assertThat(broker.answer(permissionId, new PermissionAnswer.Selected("allow_once"))).isTrue();
        assertThat(answer).isCompletedWithValue(new PermissionAnswer.Selected("allow_once"));

        assertThat(broker.find(permissionId)).isEmpty();
        assertThat(broker.answer(permissionId, new PermissionAnswer.Cancelled())).isFalse();
        assertThat(answer).isCompletedWithValue(new PermissionAnswer.Selected("allow_once"));
    }

    @Test
    void anAnswerToARequestTheAdapterWithdrewDoesNotReachIt() {
        CompletableFuture<PermissionAnswer> answer = new CompletableFuture<>();
        String permissionId = broker.register("agent-1", answer);
        // The SDK cancels the answer on $/cancel_request.
        answer.cancel(false);

        assertThat(broker.answer(permissionId, new PermissionAnswer.Selected("allow_once")))
                .as("the adapter never reads it").isFalse();
        assertThat(broker.find(permissionId)).isEmpty();
    }

    @Test
    void forgetsAWithdrawnRequestWithoutAnsweringIt() {
        CompletableFuture<PermissionAnswer> answer = new CompletableFuture<>();
        String permissionId = broker.register("agent-1", answer);

        broker.forget(permissionId);
        broker.forget(null);

        assertThat(broker.find(permissionId)).isEmpty();
        assertThat(answer).isNotDone();
    }

    @Test
    void issuesADistinctIdPerRequestAndKnowsNoOtherId() {
        String first = broker.register("agent-1", new CompletableFuture<>());
        String second = broker.register("agent-1", new CompletableFuture<>());

        assertThat(first).isNotEqualTo(second);
        assertThat(broker.find("made-up")).isEmpty();
        assertThat(broker.find(null)).isEmpty();
        assertThat(broker.answer(null, new PermissionAnswer.Cancelled())).isFalse();
    }
}
