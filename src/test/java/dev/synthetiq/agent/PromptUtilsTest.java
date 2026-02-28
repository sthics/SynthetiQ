package dev.synthetiq.agent;

import dev.synthetiq.domain.valueobject.ProjectGuide;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class PromptUtilsTest {

    @Test
    @DisplayName("withGuide includes guide section when present")
    void includesGuideWhenPresent() {
        var guide = Optional.of(new ProjectGuide("Use constructor injection only.", false));
        String result = PromptUtils.withGuide("You are a security agent.", guide, "```java\ncode\n```");

        assertThat(result).contains("You are a security agent.");
        assertThat(result).contains("--- PROJECT GUIDE ---");
        assertThat(result).contains("Use constructor injection only.");
        assertThat(result).contains("--- END PROJECT GUIDE ---");
        assertThat(result).contains("Code:\n```java\ncode\n```");
        // Verify order: base prompt -> guide -> code
        assertThat(result.indexOf("You are a security agent."))
                .isLessThan(result.indexOf("--- PROJECT GUIDE ---"));
        assertThat(result.indexOf("--- END PROJECT GUIDE ---"))
                .isLessThan(result.indexOf("Code:\n"));
    }

    @Test
    @DisplayName("withGuide omits guide section when empty")
    void omitsGuideWhenEmpty() {
        String result = PromptUtils.withGuide("You are a security agent.", Optional.empty(), "```java\ncode\n```");

        assertThat(result).contains("You are a security agent.");
        assertThat(result).doesNotContain("PROJECT GUIDE");
        assertThat(result).contains("Code:\n```java\ncode\n```");
    }
}
