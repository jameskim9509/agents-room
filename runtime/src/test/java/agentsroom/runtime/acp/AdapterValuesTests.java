package agentsroom.runtime.acp;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Masking of what an adapter reports (ADR 0004 decision 8, ADR 0005 decision 11). */
class AdapterValuesTests {

    private static final String KEY = String.join("-", "sk", "abcdefghijklmnop");

    @Test
    void masksEveryStringAtAnyDepthAndValuesUnderSecretKeys() {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("command", List.of("curl", "-H", "Authorization: Bearer abc.def.ghi", "https://example.com"));
        input.put("env", Map.of("OPENAI_API_KEY", "plain-value", "DEBUG", "1"));
        input.put("token", "opaque");
        input.put("max_tokens", 4096);
        input.put("note", "uses " + KEY);
        input.put("nested", List.of(Map.of("deep", List.of("x " + KEY))));

        @SuppressWarnings("unchecked")
        Map<String, Object> masked = (Map<String, Object>) AdapterValues.tree(input);

        assertThat(masked.get("command").toString()).contains("Authorization: ****").doesNotContain("abc.def.ghi");
        assertThat(masked.get("env")).isEqualTo(Map.of("OPENAI_API_KEY", "****", "DEBUG", "1"));
        assertThat(masked.get("token")).isEqualTo("****");
        assertThat(masked.get("max_tokens")).as("numbers stay").isEqualTo(4096);
        assertThat(masked.get("note")).isEqualTo("uses ****");
        assertThat(masked.toString()).doesNotContain(KEY);
    }

    @Test
    void copiesSoLaterChangesToTheInputDoNotReachIt() {
        List<Object> list = new ArrayList<>(List.of("a"));
        Map<String, Object> input = new LinkedHashMap<>(Map.of("list", list));

        Map<String, Object> masked = AdapterValues.object(input);
        list.add("b");

        assertThat(masked.get("list")).isEqualTo(List.of("a"));
        assertThat(AdapterValues.object(null)).isNull();
        assertThat(AdapterValues.tree(null)).isNull();
        assertThat(AdapterValues.tree(true)).isEqualTo(true);
    }
}
