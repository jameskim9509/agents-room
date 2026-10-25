package agentsroom.runtime.gateway;

import java.util.LinkedHashMap;
import java.util.Map;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * TODO(contract): temporary codec over generic maps, so the gateway works and can be tested before the
 * generated bindings exist. A frame is a JSON object whose {@code type} is a non-blank string.
 */
public final class JsonMapRendererMessageCodec implements RendererMessageCodec {

    static final String TYPE = "type";

    private static final TypeReference<Map<String, Object>> OBJECT = new TypeReference<>() {
    };

    /** Its own mapper, independent of Boot's auto-configured one. */
    private final JsonMapper mapper = JsonMapper.builder().build();

    @Override
    public ClientFrame decode(String text) throws MalformedFrameException {
        Map<String, Object> object;
        try {
            object = mapper.readValue(text, OBJECT);
        } catch (JacksonException e) {
            throw new MalformedFrameException("Frame is not a JSON object");
        }
        if (object == null) {
            throw new MalformedFrameException("Frame is not a JSON object");
        }
        if (!(object.get(TYPE) instanceof String type) || type.isBlank()) {
            throw new MalformedFrameException("Frame has no string 'type'");
        }
        Map<String, Object> body = new LinkedHashMap<>(object);
        body.remove(TYPE);
        // Map.copyOf in ClientFrame rejects null values; a JSON null reads the same as an absent field.
        body.values().removeIf(value -> value == null);
        return new ClientFrame(type, body);
    }

    @Override
    public String encode(ServerFrame frame) {
        Map<String, Object> object = new LinkedHashMap<>();
        object.put(TYPE, frame.type());
        object.putAll(frame.body());
        return mapper.writeValueAsString(object);
    }
}
