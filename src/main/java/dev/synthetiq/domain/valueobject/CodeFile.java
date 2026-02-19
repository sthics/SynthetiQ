package dev.synthetiq.domain.valueobject;

import java.util.List;

/**
 * Immutable representation of a file in a PR diff.
 */
public record CodeFile(
        String path,
        String language,
        String patch,
        int additions,
        int deletions,
        String content
) {
    public static String detectLanguage(String path) {
        if (path == null) return "unknown";
        String lower = path.toLowerCase();
        if (lower.endsWith(".java")) return "java";
        if (lower.endsWith(".xml")) return "xml";
        if (lower.endsWith(".yml") || lower.endsWith(".yaml")) return "yaml";
        if (lower.endsWith(".properties")) return "properties";
        if (lower.endsWith(".kt")) return "kotlin";
        if (lower.endsWith(".gradle") || lower.endsWith(".gradle.kts")) return "gradle";
        if (lower.endsWith(".json")) return "json";
        if (lower.endsWith(".py")) return "python";
        if (lower.endsWith(".sql")) return "sql";
        return "unknown";
    }

    public boolean isJavaSource() {
        return "java".equals(language) || "kotlin".equals(language);
    }

    public boolean isConfigFile() {
        return List.of("xml", "yaml", "properties", "gradle").contains(language);
    }

    public int estimateTokens() {
        String text = content != null ? content : patch;
        return text != null ? text.length() / 4 : 0;
    }
}
