package dev.synthetiq.agent.architecture;

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
 * Unit tests for ArchitectureAgent's file ranking logic.
 * Verifies that architecturally significant files (build configs,
 * security configs, migration signals) are ranked higher than generic files.
 */
class ArchitectureAgentRankingTest {

    private ArchitectureAgent agent;

    @BeforeEach
    void setUp() {
        AiModelRouter mockRouter = mock(AiModelRouter.class);
        AgentProperties props = new AgentProperties(
                new AgentProperties.AgentConfig(true, AiTier.SMART, 15),
                new AgentProperties.AgentConfig(true, AiTier.SMART, 15),
                new AgentProperties.AgentConfig(true, AiTier.SMART, 15),
                new AgentProperties.AgentConfig(true, AiTier.SMART, 15));
        agent = new ArchitectureAgent(mockRouter, props);
    }

    @Test
    @DisplayName("pom.xml and SecurityConfig should rank higher than README")
    void buildAndSecurityConfigRankHigherThanReadme() {
        List<CodeFile> files = List.of(
                file("README.md", "unknown", "# readme", 10, 2),
                file("pom.xml", "xml", "<dependency>jakarta</dependency>", 5, 1),
                file("src/main/java/SecurityConfig.java", "java", "class SecurityConfig {}", 20, 5),
                file("src/main/java/Utils.java", "java", "class Utils {}", 3, 1));

        List<CodeFile> ranked = agent.rankFiles(files, 15);

        // pom.xml and SecurityConfig should appear before Utils
        assertThat(ranked).isNotEmpty();
        List<String> paths = ranked.stream().map(CodeFile::path).toList();
        assertThat(paths.indexOf("pom.xml")).isLessThan(paths.indexOf("src/main/java/Utils.java"));
        assertThat(paths.indexOf("src/main/java/SecurityConfig.java"))
                .isLessThan(paths.indexOf("src/main/java/Utils.java"));
    }

    @Test
    @DisplayName("files with javax. in patch should rank higher than plain Java files")
    void javaxMigrationSignalRanksHigher() {
        List<CodeFile> files = List.of(
                file("src/main/java/Service.java", "java", "class Service {}", 5, 1),
                file("src/main/java/OldService.java", "java",
                        "import javax.inject.Inject;\nclass OldService {}", 8, 2));

        List<CodeFile> ranked = agent.rankFiles(files, 15);

        assertThat(ranked).hasSize(2);
        // OldService with javax import should rank first
        assertThat(ranked.get(0).path()).isEqualTo("src/main/java/OldService.java");
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

        List<CodeFile> ranked = agent.rankFiles(files, 3);

        assertThat(ranked).hasSize(3);
    }

    @Test
    @DisplayName("empty input should return empty list")
    void emptyInputReturnsEmptyList() {
        List<CodeFile> ranked = agent.rankFiles(List.of(), 15);
        assertThat(ranked).isEmpty();
    }

    @Test
    @DisplayName("non-relevant files (e.g. markdown) should be filtered out")
    void nonRelevantFilesFilteredOut() {
        List<CodeFile> files = List.of(
                file("README.md", "unknown", "# readme", 10, 2),
                file("CHANGELOG.md", "unknown", "## v1.0", 5, 0),
                file("pom.xml", "xml", "<project>...</project>", 50, 10));

        List<CodeFile> ranked = agent.rankFiles(files, 15);

        // Only pom.xml should pass the filter (java/config/gradle)
        assertThat(ranked).hasSize(1);
        assertThat(ranked.get(0).path()).isEqualTo("pom.xml");
    }

    @Test
    @DisplayName("controller files should rank higher than generic service files")
    void controllerRanksHigherThanService() {
        List<CodeFile> files = List.of(
                file("src/main/java/UserService.java", "java", "class UserService {}", 10, 2),
                file("src/main/java/UserController.java", "java", "class UserController {}", 10, 2));

        List<CodeFile> ranked = agent.rankFiles(files, 15);

        assertThat(ranked).hasSize(2);
        assertThat(ranked.get(0).path()).isEqualTo("src/main/java/UserController.java");
    }

    private CodeFile file(String path, String language, String patch, int additions, int deletions) {
        return new CodeFile(path, language, patch, additions, deletions, null);
    }
}
