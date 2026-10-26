package agentsroom.runtime.shell;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ReadyLineAnnouncerTests {

    @Test
    void readyLineIsThePrefixAndACompactJsonObject() {
        assertThat(ReadyLineAnnouncer.readyLine(43123)).isEqualTo("AGENTS_ROOM_READY {\"port\":43123}");
    }

    @Test
    void readyLineHasNoLineBreakOfItsOwn() {
        // ControlChannel appends exactly one "\n"; a "\r" would break exact-match readers.
        assertThat(ReadyLineAnnouncer.readyLine(1)).doesNotContain("\n", "\r");
    }
}
