package dev.synthetiq.agent.orchestrator;

import dev.synthetiq.domain.entity.AgentResult;
import dev.synthetiq.domain.valueobject.ProjectGuide;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Builds the GitHub PR review output: a severity-grouped summary comment
 * and inline comments on the diff for CRITICAL/HIGH findings.
 */
@Component
public class ReviewCommentBuilder {

    private static final Logger log = LoggerFactory.getLogger(ReviewCommentBuilder.class);
    private static final int MAX_INLINE_COMMENTS = 10;

    private final ObjectMapper objectMapper;

    public ReviewCommentBuilder(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * Result of building review output: summary body, review event, and inline comments.
     * The orchestrator wraps this into a ReviewComment with the correct installationId.
     */
    record BuildResult(String body, String event, List<InlineComment> inlineComments) {}

    /**
     * Builds the review output: severity-grouped summary body and inline comments.
     */
    public BuildResult build(List<AgentResult> results,
            String repoFullName, int prNumber,
            String headSha,
            Optional<ProjectGuide> guide) {
        List<ParsedFinding> allFindings = new ArrayList<>();
        List<FailedAgent> failedAgents = new ArrayList<>();

        // Parse all findings from all agents
        for (AgentResult result : results) {
            String agentName = result.getAgentType().name().charAt(0)
                    + result.getAgentType().name().substring(1).toLowerCase();
            if (!result.isSuccess()) {
                failedAgents.add(new FailedAgent(agentName, result.getErrorMessage()));
                continue;
            }
            parseFindings(result, allFindings, agentName);
        }

        // Separate by severity
        List<ParsedFinding> critical = allFindings.stream()
                .filter(f -> "CRITICAL".equals(f.severity)).toList();
        List<ParsedFinding> high = allFindings.stream()
                .filter(f -> "HIGH".equals(f.severity)).toList();
        List<ParsedFinding> medium = allFindings.stream()
                .filter(f -> "MEDIUM".equals(f.severity)).toList();
        List<ParsedFinding> low = allFindings.stream()
                .filter(f -> "LOW".equals(f.severity) || "INFO".equals(f.severity)).toList();

        // Build summary body
        String body = buildSummary(critical, high, medium, low, failedAgents,
                repoFullName, prNumber, headSha, guide);

        // Build inline comments for CRITICAL + HIGH with line numbers
        List<InlineComment> inlineComments = buildInlineComments(critical, high);

        // Determine review event
        String event = critical.isEmpty()
                ? (high.isEmpty() ? "APPROVE" : "COMMENT")
                : "REQUEST_CHANGES";

        return new BuildResult(body, event, inlineComments);
    }

    private String buildSummary(List<ParsedFinding> critical, List<ParsedFinding> high,
            List<ParsedFinding> medium, List<ParsedFinding> low,
            List<FailedAgent> failedAgents,
            String repoFullName, int prNumber, String headSha,
            Optional<ProjectGuide> guide) {
        StringBuilder sb = new StringBuilder();
        sb.append("## SynthetiQ Code Review\n\n");

        // Severity counts bar
        sb.append("**%d** critical · **%d** high · **%d** medium · %d low\n\n"
                .formatted(critical.size(), high.size(), medium.size(), low.size()));

        // Critical section
        if (!critical.isEmpty()) {
            sb.append("### Critical\n");
            for (ParsedFinding f : critical) {
                sb.append("- :red_circle: **%s** — `%s%s`\n"
                        .formatted(f.title, f.file, f.line > 0 ? ":" + f.line : ""));
            }
            sb.append("\n");
        }

        // High section
        if (!high.isEmpty()) {
            sb.append("### High\n");
            for (ParsedFinding f : high) {
                sb.append("- :orange_circle: **%s** — `%s%s`\n"
                        .formatted(f.title, f.file, f.line > 0 ? ":" + f.line : ""));
            }
            sb.append("\n");
        }

        // Medium + Low in collapsible details
        if (!medium.isEmpty() || !low.isEmpty()) {
            sb.append("<details><summary>%d medium · %d low findings</summary>\n\n"
                    .formatted(medium.size(), low.size()));
            for (ParsedFinding f : medium) {
                sb.append("- :yellow_circle: %s — `%s`\n".formatted(f.title, f.file));
            }
            for (ParsedFinding f : low) {
                sb.append("- :white_circle: %s — `%s`\n".formatted(f.title, f.file));
            }
            sb.append("\n</details>\n\n");
        }

        // Failed agents
        for (FailedAgent fa : failedAgents) {
            sb.append("> :warning: **%s Agent** failed: %s\n\n".formatted(fa.name, fa.error));
        }

        // Guide tip
        if (guide.isEmpty()) {
            sb.append("> :bulb: **Tip:** Add a `SYNTHETIQ.md` to your repo root to get project-aware reviews.\n\n");
        } else if (guide.get().truncated()) {
            sb.append("> :warning: Your `SYNTHETIQ.md` exceeds 8KB and was truncated.\n\n");
        }

        sb.append("---\n");
        sb.append("*Powered by [SynthetiQ](https://github.com/sthics/SynthetiQ) — Multi-Agent Code Review Platform*");

        return sb.toString();
    }

    private List<InlineComment> buildInlineComments(List<ParsedFinding> critical,
            List<ParsedFinding> high) {
        List<InlineComment> comments = new ArrayList<>();

        // Critical first, then high — cap at MAX_INLINE_COMMENTS
        for (ParsedFinding f : critical) {
            if (comments.size() >= MAX_INLINE_COMMENTS)
                break;
            if (f.line <= 0)
                continue;
            comments.add(buildInlineComment(f));
        }
        for (ParsedFinding f : high) {
            if (comments.size() >= MAX_INLINE_COMMENTS)
                break;
            if (f.line <= 0)
                continue;
            comments.add(buildInlineComment(f));
        }

        return comments;
    }

    private InlineComment buildInlineComment(ParsedFinding f) {
        StringBuilder body = new StringBuilder();
        String badge = "CRITICAL".equals(f.severity) ? ":red_circle: **CRITICAL**" : ":orange_circle: **HIGH**";
        body.append("%s · %s\n\n".formatted(badge, f.agentName));
        body.append("**%s**\n\n".formatted(f.title));

        if (f.description != null && !f.description.isBlank()) {
            body.append("%s\n\n".formatted(f.description));
        }

        // Add code suggestion
        if (f.suggestedCode != null && !f.suggestedCode.isBlank()) {
            if ("replacement".equals(f.suggestionType)) {
                body.append("```suggestion\n%s\n```\n\n".formatted(f.suggestedCode));
            } else {
                body.append("```diff\n%s\n```\n\n".formatted(f.suggestedCode));
            }
        } else if (f.suggestion != null && !f.suggestion.isBlank()) {
            body.append("> %s\n\n".formatted(f.suggestion));
        }

        body.append("*Found by SynthetiQ %s Agent*".formatted(f.agentName));
        return new InlineComment(f.file, f.line, body.toString());
    }

    private void parseFindings(AgentResult result, List<ParsedFinding> allFindings, String agentName) {
        try {
            JsonNode root = objectMapper.readTree(result.getFindings());
            JsonNode findings = root.has("findings") ? root.get("findings") : root;
            if (!findings.isArray())
                return;

            for (JsonNode f : findings) {
                allFindings.add(new ParsedFinding(
                        textOrDefault(f, "severity", "INFO").toUpperCase(),
                        textOrDefault(f, "file", "—"),
                        f.has("line") && !f.get("line").isNull() ? f.get("line").asInt() : 0,
                        textOrDefault(f, "title", textOrDefault(f, "description", "—")),
                        textOrDefault(f, "description", null),
                        textOrDefault(f, "suggestion", null),
                        textOrDefault(f, "suggested_code", null),
                        textOrDefault(f, "suggestion_type", null),
                        agentName));
            }
        } catch (Exception e) {
            log.warn("Failed to parse findings JSON for {} agent: {}", result.getAgentType(), e.getMessage());
        }
    }

    private static String textOrDefault(JsonNode node, String field, String fallback) {
        return node.has(field) && !node.get(field).isNull() ? node.get(field).asText() : fallback;
    }

    private record ParsedFinding(String severity, String file, int line, String title,
            String description, String suggestion,
            String suggestedCode, String suggestionType,
            String agentName) {
    }

    private record FailedAgent(String name, String error) {
    }
}
