package dev.synthetiq.agent.security;

import dev.synthetiq.config.AgentProperties;
import dev.synthetiq.domain.enums.AiTier;
import dev.synthetiq.domain.valueobject.CodeFile;
import dev.synthetiq.infrastructure.ai.AiModelRouter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Unit tests for SecurityAgent's file ranking logic.
 * Verifies that security-sensitive files (auth code, secrets, controllers)
 * are ranked higher than generic utility files.
 */
class SecurityAgentRankingTest {

    private SecurityAgent agent;

    @BeforeEach
    void setUp() {
        AiModelRouter mockRouter = mock(AiModelRouter.class);
        AgentProperties props = new AgentProperties(
                new AgentProperties.AgentConfig(true, AiTier.CHEAP, 15),
                new AgentProperties.AgentConfig(true, AiTier.CHEAP, 15),
                new AgentProperties.AgentConfig(true, AiTier.CHEAP, 15),
                new AgentProperties.AgentConfig(true, AiTier.CHEAP, 15));
        agent = new SecurityAgent(mockRouter, props);
    }

    @Test
    @DisplayName("SecurityConfig and auth files should rank highest")
    void securityConfigRanksHighest() {
        List<CodeFile> files = List.of(
                file("src/main/java/Utils.java", "java", "class Utils {}", 5, 1),
                file("src/main/java/SecurityConfig.java", "java", "class SecurityConfig {}", 10, 3),
                file("src/main/java/AuthFilter.java", "java", "class AuthFilter {}", 8, 2));

        List<CodeFile> ranked = agent.rankFiles(files, 15);

        assertThat(ranked).hasSize(3);
        // SecurityConfig and AuthFilter should both rank above Utils
        List<String> paths = ranked.stream().map(CodeFile::path).toList();
        assertThat(paths.indexOf("src/main/java/SecurityConfig.java"))
                .isLessThan(paths.indexOf("src/main/java/Utils.java"));
        assertThat(paths.indexOf("src/main/java/AuthFilter.java"))
                .isLessThan(paths.indexOf("src/main/java/Utils.java"));
    }

    @Test
    @DisplayName("files with password/secret in patch should rank high")
    void secretsInPatchRankHigh() {
        List<CodeFile> files = List.of(
                file("src/main/java/Service.java", "java", "class Service {}", 5, 1),
                file("src/main/java/DbConfig.java", "java",
                        "String password = \"admin123\";", 3, 0));

        List<CodeFile> ranked = agent.rankFiles(files, 15);

        assertThat(ranked).hasSize(2);
        assertThat(ranked.get(0).path()).isEqualTo("src/main/java/DbConfig.java");
    }

    @Test
    @DisplayName("controller files should rank above utility files")
    void controllerRanksAboveUtility() {
        List<CodeFile> files = List.of(
                file("src/main/java/StringUtils.java", "java", "class StringUtils {}", 10, 2),
                file("src/main/java/UserController.java", "java",
                        "@RestController class UserController {}", 15, 3));

        List<CodeFile> ranked = agent.rankFiles(files, 15);

        assertThat(ranked).hasSize(2);
        assertThat(ranked.get(0).path()).isEqualTo("src/main/java/UserController.java");
    }

    @Test
    @DisplayName("output should be capped at maxFiles")
    void outputCappedAtMaxFiles() {
        List<CodeFile> files = List.of(
                file("src/A.java", "java", "A", 1, 0),
                file("src/B.java", "java", "B", 1, 0),
                file("src/C.java", "java", "C", 1, 0),
                file("src/D.java", "java", "D", 1, 0),
                file("src/E.java", "java", "E", 1, 0));

        List<CodeFile> ranked = agent.rankFiles(files, 2);

        assertThat(ranked).hasSize(2);
    }

    @Test
    @DisplayName("empty input should return empty list")
    void emptyInputReturnsEmptyList() {
        List<CodeFile> ranked = agent.rankFiles(List.of(), 15);
        assertThat(ranked).isEmpty();
    }

    @Test
    @DisplayName("files with SQL keywords in patch should rank higher")
    void sqlKeywordsRankHigher() {
        List<CodeFile> files = List.of(
                file("src/main/java/Helper.java", "java", "class Helper {}", 5, 1),
                file("src/main/java/UserDao.java", "java",
                        "String sql = \"SELECT * FROM users WHERE id = \" + id;", 10, 2));

        List<CodeFile> ranked = agent.rankFiles(files, 15);

        assertThat(ranked).hasSize(2);
        // UserDao has both "dao" in path (+60) and "select " in patch (+70) = 130+
        assertThat(ranked.get(0).path()).isEqualTo("src/main/java/UserDao.java");
    }

    @Test
    @DisplayName("non-relevant files (markdown, images) should be filtered out")
    void nonRelevantFilesFilteredOut() {
        List<CodeFile> files = List.of(
                file("README.md", "unknown", "# readme", 10, 2),
                file("logo.png", "unknown", "", 0, 0),
                file("src/main/java/App.java", "java", "class App {}", 5, 1));

        List<CodeFile> ranked = agent.rankFiles(files, 15);

        assertThat(ranked).hasSize(1);
        assertThat(ranked.get(0).path()).isEqualTo("src/main/java/App.java");
    }

    private CodeFile file(String path, String language, String patch, int additions, int deletions) {
        return new CodeFile(path, language, patch, additions, deletions, null);
    }
}
