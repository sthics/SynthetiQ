package dev.synthetiq.domain.valueobject;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class ProjectGuideTest {

    @Test
    @DisplayName("of() returns guide with content and truncated=false when under limit")
    void underLimit() {
        Optional<ProjectGuide> guide = ProjectGuide.of("# My Rules\n- Use constructor injection");
        assertThat(guide).isPresent();
        assertThat(guide.get().content()).isEqualTo("# My Rules\n- Use constructor injection");
        assertThat(guide.get().truncated()).isFalse();
    }

    @Test
    @DisplayName("of() returns empty for null input")
    void nullReturnsEmpty() {
        assertThat(ProjectGuide.of(null)).isEmpty();
    }

    @Test
    @DisplayName("of() returns empty for blank input")
    void blankReturnsEmpty() {
        assertThat(ProjectGuide.of("   ")).isEmpty();
    }

    @Test
    @DisplayName("of() truncates content exceeding 8KB at last complete line")
    void truncatesAtLineBreak() {
        String line = "x".repeat(100) + "\n"; // 101 bytes per line
        int linesNeeded = (8192 / 101) + 2;
        String oversized = line.repeat(linesNeeded);
        assertThat(oversized.length()).isGreaterThan(8192);

        Optional<ProjectGuide> guide = ProjectGuide.of(oversized);

        assertThat(guide).isPresent();
        assertThat(guide.get().truncated()).isTrue();
        assertThat(guide.get().content().length()).isLessThanOrEqualTo(8192);
        assertThat(guide.get().content()).endsWith("\n");
    }

    @Test
    @DisplayName("of() hard-cuts at 8KB when no newlines exist in oversized content")
    void hardCutsWithoutNewlines() {
        String noNewlines = "x".repeat(10000);
        Optional<ProjectGuide> guide = ProjectGuide.of(noNewlines);

        assertThat(guide).isPresent();
        assertThat(guide.get().truncated()).isTrue();
        assertThat(guide.get().content().length()).isEqualTo(8192);
    }

    @Test
    @DisplayName("of() handles content exactly at 8KB limit")
    void exactlyAtLimit() {
        String exact = "a".repeat(8192);
        Optional<ProjectGuide> guide = ProjectGuide.of(exact);
        assertThat(guide).isPresent();
        assertThat(guide.get().truncated()).isFalse();
        assertThat(guide.get().content()).isEqualTo(exact);
    }
}
