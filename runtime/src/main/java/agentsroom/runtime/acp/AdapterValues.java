package agentsroom.runtime.acp;

import agentsroom.runtime.logging.SecretMasker;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Masks what an adapter reported before it leaves the runtime (ADR 0004 decision 8, ADR 0005 decision
 * 11): every string, at any depth, passes {@link SecretMasker}, and a string under a secret-looking key
 * ({@code "OPENAI_API_KEY": "..."}, {@code "token": "..."}) is masked whole, since a text pattern
 * cannot see the key it belongs to. Numbers, booleans and {@code null} stay as they are. The result is a
 * fresh copy made of maps, lists and scalars, so later changes to the input never reach it.
 */
public final class AdapterValues {

    private AdapterValues() {
    }

    /** A masked string, or {@code null}. */
    public static String text(String value) {
        return SecretMasker.mask(value);
    }

    /** A masked deep copy of a JSON value (maps, lists, strings, numbers, booleans). */
    public static Object tree(Object value) {
        if (value instanceof String text) {
            return SecretMasker.mask(text);
        }
        if (value instanceof Map<?, ?> map) {
            return object(map);
        }
        if (value instanceof List<?> list) {
            List<Object> copy = new ArrayList<>(list.size());
            list.forEach(item -> copy.add(tree(item)));
            return copy;
        }
        return value;
    }

    /** A masked deep copy of a JSON object, or {@code null}. */
    public static Map<String, Object> object(Map<?, ?> value) {
        if (value == null) {
            return null;
        }
        Map<String, Object> copy = new LinkedHashMap<>();
        value.forEach((key, item) -> {
            String name = String.valueOf(key);
            Object masked = SecretMasker.isSecretName(name) && item instanceof String ? SecretMasker.MASK : tree(item);
            copy.put(SecretMasker.mask(name), masked);
        });
        return copy;
    }

    /** Masked deep copies of a list of JSON objects, or {@code null}. */
    public static List<Map<String, Object>> objects(List<Map<String, Object>> values) {
        if (values == null) {
            return null;
        }
        List<Map<String, Object>> copy = new ArrayList<>(values.size());
        values.forEach(value -> copy.add(object(value)));
        return copy;
    }
}
