package agentsroom.runtime.gateway;

/**
 * What a {@code commandRejected} frame echoes from the command it answers (ADR 0005): the command's
 * {@code type} and the ids it carried, so that the renderer can route the rejection to the card or
 * dialog it came from. An id the command did not carry is {@code null} and left out of the frame.
 *
 * @param command the command's {@code type}, or {@value #UNKNOWN_COMMAND} when it had none
 */
record CommandEcho(String command, String agentId, String projectId, String permissionId) {

    /** The {@code command} of a frame without a string {@code type}. */
    static final String UNKNOWN_COMMAND = "unknown";

    static CommandEcho of(String command) {
        return new CommandEcho(command, null, null, null);
    }

    static CommandEcho agent(String command, String agentId) {
        return new CommandEcho(command, agentId, null, null);
    }

    static CommandEcho project(String command, String projectId) {
        return new CommandEcho(command, null, projectId, null);
    }

    static CommandEcho permission(String command, String permissionId) {
        return new CommandEcho(command, null, null, permissionId);
    }
}
