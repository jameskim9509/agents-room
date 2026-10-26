package agentsroom.runtime.acp;

import java.util.List;
import java.util.Map;

/**
 * The adapter's {@code session/new} answer, masked.
 *
 * @param modes the ACP SessionModeState as JSON, or {@code null}
 * @param configOptions the ACP SessionConfigOption list as JSON, or {@code null}
 */
public record OpenedSession(String sessionId, Map<String, Object> modes, List<Map<String, Object>> configOptions) {
}
