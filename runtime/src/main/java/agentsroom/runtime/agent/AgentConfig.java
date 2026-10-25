package agentsroom.runtime.agent;

import java.util.UUID;

/**
 * One agent of a project: which harness runs it, against which model provider, model and auth method
 * (architecture.md, Agent config registry). It holds no secrets (ADR 0004).
 *
 * @param agentId runtime-issued UUID; persisted, so it survives restarts and stays 1:1 with a character
 * @param projectId the owning project
 * @param name display name
 * @param harnessId e.g. {@code codex}
 * @param providerId e.g. {@code openai}
 * @param modelId {@code null} means the harness default
 * @param authMethodId e.g. {@code chat-gpt}
 * @param palette character palette 0-5, assigned by the runtime
 * @param hueShift character hue shift in degrees, assigned by the runtime
 */
public record AgentConfig(
        String agentId,
        String projectId,
        String name,
        String harnessId,
        String providerId,
        String modelId,
        String authMethodId,
        int palette,
        int hueShift) {

    public static final int PALETTE_COUNT = 6;

    /** A shifted hue is 45-315 degrees (ux.md); 0 means no shift. */
    static final int MIN_HUE_SHIFT = 45;
    static final int MAX_HUE_SHIFT = 315;

    /** Whether {@code hueShift} is 0 or 45-315, the values the contract allows. */
    public static boolean isValidHueShift(int hueShift) {
        return hueShift == 0 || (hueShift >= MIN_HUE_SHIFT && hueShift <= MAX_HUE_SHIFT);
    }

    /**
     * The agent seeded on a project's first open (D10), until "add agent" exists (SCRUM-25, 26): Codex
     * with a ChatGPT login and the harness's default model.
     */
    public static AgentConfig seededCodex(String projectId) {
        return new AgentConfig(
                UUID.randomUUID().toString(), projectId, "Codex", "codex", "openai", null, "chat-gpt", 0, 0);
    }
}
