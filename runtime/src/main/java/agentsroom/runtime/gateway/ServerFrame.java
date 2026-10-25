package agentsroom.runtime.gateway;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A runtime→renderer frame.
 *
 * <p>TODO(contract): temporary. Replace with the generated {@code ServerMessage} bindings from
 * {@code core/asyncapi.yaml} once the contract PR lands.
 *
 * @param type the frame's {@code type} discriminator
 * @param body every other top-level field; values are maps, lists, strings, numbers, booleans or
 *     {@code null}
 */
public record ServerFrame(String type, Map<String, Object> body) {

    public ServerFrame {
        // Keeps field order and allows null values (absent and null differ on the wire, D3).
        body = Collections.unmodifiableMap(new LinkedHashMap<>(body));
    }
}
