package agentsroom.runtime.session;

import agentsroom.contract.ServerMessage;
import agentsroom.runtime.acp.AgentConnectionFactory;
import agentsroom.runtime.harness.HarnessLauncher;
import agentsroom.runtime.permission.PermissionBroker;
import java.time.Clock;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.Consumer;

/**
 * What every {@link AgentController} uses, from the {@link AgentSupervisor}.
 *
 * @param summariesChanged called under the publish lock after a change that can move an agent's
 *     readiness; it appends an {@code agentsUpdated} frame when the list renderers saw differs
 */
record AgentServices(
        StatePublisher publisher,
        HarnessLauncher launcher,
        AgentConnectionFactory connections,
        PermissionBroker broker,
        AgentTimeouts timeouts,
        Clock clock,
        ScheduledExecutorService scheduler,
        Consumer<List<ServerMessage>> summariesChanged) {
}
