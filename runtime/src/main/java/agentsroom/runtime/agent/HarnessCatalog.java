package agentsroom.runtime.agent;

import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The static harness catalog (architecture.md, Agent config registry): the harnesses this runtime
 * knows, the provider and auth method pairs each runs with, and what each reports (ux.md R3, R4).
 * Nothing here is learned by watching a run.
 *
 * <p>The adapter install check ({@code executable_missing}) and the adapter's auth status
 * ({@code auth_required}) come with the adapter launch (SCRUM-23, 65); until then readiness rests on
 * this catalog alone.
 */
public final class HarnessCatalog {

    /**
     * One harness.
     *
     * @param authMethodsByProvider the authMethodIds it runs with, per providerId
     * @param reportsToolDetails whether its tool calls carry details (ux.md R3)
     * @param reportsSubagentSessions whether it reports child sessions (ux.md R4)
     */
    public record Harness(
            String harnessId,
            Map<String, Set<String>> authMethodsByProvider,
            boolean reportsToolDetails,
            boolean reportsSubagentSessions) {

        public Harness {
            authMethodsByProvider = Map.copyOf(authMethodsByProvider);
        }

        public boolean supports(String providerId, String authMethodId) {
            if (providerId == null || authMethodId == null) {
                return false;
            }
            return authMethodsByProvider.getOrDefault(providerId, Set.of()).contains(authMethodId);
        }
    }

    /**
     * Codex (SCRUM-23, 65) with a ChatGPT login. codex-acp tags its tool calls with kind and status
     * (D21). Without the {@code subagents} client capability, which agents-room does not advertise, it
     * sends sub-agents as ordinary tool calls and reports no child sessions (D22). Codex with Bedrock
     * stays out until its spike shows it runs (ADR 0004, SCRUM-26).
     */
    static final Harness CODEX = new Harness("codex", Map.of("openai", Set.of("chat-gpt")), true, false);

    private static final Map<String, Harness> HARNESSES = Map.of(CODEX.harnessId(), CODEX);

    private HarnessCatalog() {
    }

    /** The harness, or empty when this runtime does not know it. */
    public static Optional<Harness> find(String harnessId) {
        // Map.of(...).get(null) throws.
        return harnessId == null ? Optional.empty() : Optional.ofNullable(HARNESSES.get(harnessId));
    }

    /** Whether the catalog says {@code agent}'s harness runs with its provider and auth method. */
    public static boolean supports(AgentConfig agent) {
        return find(agent.harnessId())
                .map(harness -> harness.supports(agent.providerId(), agent.authMethodId()))
                .orElse(false);
    }

    /** ux.md R3. {@code false} for a harness the catalog does not know: it promises nothing. */
    public static boolean reportsToolDetails(AgentConfig agent) {
        return find(agent.harnessId()).map(Harness::reportsToolDetails).orElse(false);
    }

    /** ux.md R4. {@code false} for a harness the catalog does not know. */
    public static boolean reportsSubagentSessions(AgentConfig agent) {
        return find(agent.harnessId()).map(Harness::reportsSubagentSessions).orElse(false);
    }
}
