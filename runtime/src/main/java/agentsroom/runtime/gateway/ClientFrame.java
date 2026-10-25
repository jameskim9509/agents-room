package agentsroom.runtime.gateway;

import java.util.Map;
import java.util.Optional;

/**
 * A decoded renderer→runtime frame (a command, ADR 0005).
 *
 * <p>TODO(contract): temporary. Replace with the generated {@code ClientMessage} bindings from
 * {@code core/asyncapi.yaml} once the contract PR lands; {@link #type()} is its discriminator.
 *
 * @param type the frame's {@code type} discriminator
 * @param body every other top-level field
 */
public record ClientFrame(String type, Map<String, Object> body) {

    public ClientFrame {
        body = Map.copyOf(body);
    }

    /** The field as a string, or empty when it is missing or not a string. */
    public Optional<String> string(String field) {
        return body.get(field) instanceof String value ? Optional.of(value) : Optional.empty();
    }
}
