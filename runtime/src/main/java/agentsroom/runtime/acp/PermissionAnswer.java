package agentsroom.runtime.acp;

/** The answer to a {@link PermissionAsk}: ACP {@code RequestPermissionOutcome}. */
public sealed interface PermissionAnswer {

    /** The user picked this option. */
    record Selected(String optionId) implements PermissionAnswer {
    }

    /** The turn was cancelled or the agent stopped before the user answered (ACP Prompt Turn). */
    record Cancelled() implements PermissionAnswer {
    }
}
