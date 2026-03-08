package dev.synthetiq.agent.orchestrator;

import dev.synthetiq.domain.entity.AgentResult;
import dev.synthetiq.domain.enums.AgentType;
import dev.synthetiq.domain.enums.AiTier;
import dev.synthetiq.domain.valueobject.ProjectGuide;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class ReviewCommentBuilderTest {

    private ReviewCommentBuilder builder;

    @BeforeEach
    void setUp() {
        builder = new ReviewCommentBuilder(new ObjectMapper());
    }

    @Test
    void summary_groupsBySeverity_criticalAndHighShown() {
        AgentResult result = AgentResult.success(AgentType.SECURITY, AiTier.SMART,
                """
                        {"findings":[
                            {"severity":"CRITICAL","file":"src/Db.java","line":10,"title":"SQL injection","suggestion":"Use parameterized queries","suggested_code":"stmt.setString(1, id);","suggestion_type":"replacement"},
                            {"severity":"HIGH","file":"src/Api.java","line":25,"title":"Missing auth check","suggestion":"Add auth","suggested_code":"","suggestion_type":"conceptual"},
                            {"severity":"MEDIUM","file":"src/App.java","line":5,"title":"Unused import","suggestion":"Remove it"}
                        ],"summary":"Found issues"}
                        """,
                "Found issues", 100, 50, Duration.ofSeconds(2));

        var comment = builder.build(List.of(result), "owner/repo", 42, "abc1234", Optional.empty());

        // Summary should have severity header counts
        assertThat(comment.body()).contains("**1** critical");
        assertThat(comment.body()).contains("**1** high");
        assertThat(comment.body()).contains("1 medium");
        // CRITICAL and HIGH listed with titles
        assertThat(comment.body()).contains("SQL injection");
        assertThat(comment.body()).contains("Missing auth check");
        // MEDIUM in collapsed details
        assertThat(comment.body()).contains("<details>");
        assertThat(comment.body()).contains("Unused import");
    }

    @Test
    void inlineComments_generatedForCriticalAndHigh() {
        AgentResult result = AgentResult.success(AgentType.SECURITY, AiTier.SMART,
                """
                        {"findings":[
                            {"severity":"CRITICAL","file":"src/Db.java","line":10,"title":"SQL injection","description":"Concatenated SQL","suggestion":"Use prepared statements","suggested_code":"preparedStatement.setString(1, id);","suggestion_type":"replacement"},
                            {"severity":"MEDIUM","file":"src/App.java","line":5,"title":"Unused import","suggestion":"Remove it"}
                        ],"summary":"Found issues"}
                        """,
                "Found issues", 100, 50, Duration.ofSeconds(2));

        var comment = builder.build(List.of(result), "owner/repo", 42, "abc1234", Optional.empty());

        assertThat(comment.inlineComments()).hasSize(1);
        assertThat(comment.inlineComments().get(0).path()).isEqualTo("src/Db.java");
        assertThat(comment.inlineComments().get(0).line()).isEqualTo(10);
        assertThat(comment.inlineComments().get(0).body()).contains("```suggestion");
        assertThat(comment.inlineComments().get(0).body()).contains("preparedStatement.setString(1, id);");
    }

    @Test
    void inlineComment_conceptualType_usesDiffBlock() {
        AgentResult result = AgentResult.success(AgentType.ARCHITECTURE, AiTier.SMART,
                """
                        {"findings":[
                            {"severity":"HIGH","file":"src/Service.java","line":30,"title":"Missing error handling","description":"No try-catch","suggestion":"Add error handling","suggested_code":"try {\\n    response = client.fetch(url);\\n} catch (Exception e) {\\n    throw new ServiceException(e);\\n}","suggestion_type":"conceptual"}
                        ],"summary":"Found issues"}
                        """,
                "Found issues", 100, 50, Duration.ofSeconds(2));

        var comment = builder.build(List.of(result), "owner/repo", 42, "abc1234", Optional.empty());

        assertThat(comment.inlineComments()).hasSize(1);
        assertThat(comment.inlineComments().get(0).body()).contains("```diff");
        assertThat(comment.inlineComments().get(0).body()).doesNotContain("```suggestion");
    }

    @Test
    void noLineNumber_findingGoesToSummaryOnly() {
        AgentResult result = AgentResult.success(AgentType.ARCHITECTURE, AiTier.SMART,
                """
                        {"findings":[
                            {"severity":"HIGH","file":"src/Service.java","title":"Missing interface extraction","suggestion":"Extract interface"}
                        ],"summary":"Needs refactoring"}
                        """,
                "Needs refactoring", 100, 50, Duration.ofSeconds(2));

        var comment = builder.build(List.of(result), "owner/repo", 42, "abc1234", Optional.empty());

        // Should appear in summary
        assertThat(comment.body()).contains("Missing interface extraction");
        // No inline comment (no line number)
        assertThat(comment.inlineComments()).isEmpty();
    }

    @Test
    void inlineComments_cappedAt10() {
        StringBuilder json = new StringBuilder("{\"findings\":[");
        for (int i = 0; i < 15; i++) {
            if (i > 0)
                json.append(",");
            json.append(
                    """
                            {"severity":"CRITICAL","file":"src/File%d.java","line":%d,"title":"Issue %d","suggestion":"Fix it","suggested_code":"fixed();","suggestion_type":"replacement"}
                            """
                            .formatted(i, i + 1, i));
        }
        json.append("],\"summary\":\"Many issues\"}");

        AgentResult result = AgentResult.success(AgentType.SECURITY, AiTier.SMART,
                json.toString(), "Many issues", 100, 50, Duration.ofSeconds(2));

        var comment = builder.build(List.of(result), "owner/repo", 42, "abc1234", Optional.empty());

        assertThat(comment.inlineComments()).hasSize(10);
    }

    @Test
    void failedAgent_showsWarningInSummary() {
        AgentResult failed = AgentResult.failure(AgentType.SECURITY, "Model timeout");

        var comment = builder.build(List.of(failed), "owner/repo", 42, "abc1234", Optional.empty());

        assertThat(comment.body()).contains("Security");
        assertThat(comment.body()).contains("Model timeout");
        assertThat(comment.inlineComments()).isEmpty();
    }

    @Test
    void projectGuideTip_shownWhenGuideAbsent() {
        AgentResult result = AgentResult.success(AgentType.SECURITY, AiTier.SMART,
                "{\"findings\":[],\"summary\":\"Clean\"}", "Clean", 100, 50, Duration.ofSeconds(2));

        var comment = builder.build(List.of(result), "owner/repo", 42, "abc1234", Optional.empty());

        assertThat(comment.body()).contains("SYNTHETIQ.md");
    }

    @Test
    void projectGuideTip_notShownWhenGuidePresent() {
        AgentResult result = AgentResult.success(AgentType.SECURITY, AiTier.SMART,
                "{\"findings\":[],\"summary\":\"Clean\"}", "Clean", 100, 50, Duration.ofSeconds(2));

        var comment = builder.build(List.of(result), "owner/repo", 42, "abc1234",
                Optional.of(new ProjectGuide("guide content", false)));

        assertThat(comment.body()).doesNotContain("Add a `SYNTHETIQ.md`");
    }
}
