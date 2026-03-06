package dev.synthetiq.agent.performance;

import dev.synthetiq.agent.AgentAnalysisResult;
import dev.synthetiq.config.AgentProperties;
import dev.synthetiq.domain.enums.AgentType;
import dev.synthetiq.domain.enums.AiTier;
import dev.synthetiq.domain.valueobject.CodeFile;
import dev.synthetiq.domain.valueobject.ProjectGuide;
import dev.synthetiq.infrastructure.ai.AiModelRouter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PerformanceAgentTest {

    private AiModelRouter mockRouter;
    private PerformanceAgent agent;

    @BeforeEach
    void setUp() {
        mockRouter = mock(AiModelRouter.class);
        AgentProperties props = new AgentProperties(
                new AgentProperties.AgentConfig(true, AiTier.CHEAP, 15),
                new AgentProperties.AgentConfig(true, AiTier.CHEAP, 15),
                new AgentProperties.AgentConfig(true, AiTier.CHEAP, 15),
                new AgentProperties.AgentConfig(true, AiTier.CHEAP, 15));
        agent = new PerformanceAgent(mockRouter, props);
    }

    @Test
    @DisplayName("agent type is PERFORMANCE")
    void agentType() {
        assertThat(agent.getType()).isEqualTo(AgentType.PERFORMANCE);
    }

    @Test
    @DisplayName("minimum tier is CHEAP")
    void minimumTier() {
        assertThat(agent.getMinimumTier()).isEqualTo(AiTier.CHEAP);
    }

    @Nested
    @DisplayName("supports()")
    class Supports {

        @Test
        @DisplayName("supports files when Java source present")
        void supportsJavaFiles() {
            List<CodeFile> files = List.of(
                    file("src/main/java/Service.java", "java", "class Service {}", 5, 1));
            assertThat(agent.supports(files)).isTrue();
        }

        @Test
        @DisplayName("does not support when only non-Java files")
        void doesNotSupportOnlyConfig() {
            List<CodeFile> files = List.of(
                    file("README.md", "unknown", "# readme", 5, 1),
                    file("logo.png", "unknown", "", 0, 0));
            assertThat(agent.supports(files)).isFalse();
        }
    }

    @Nested
    @DisplayName("rankFiles()")
    class RankFiles {

        @Test
        @DisplayName("repository/DAO classes rank highest")
        void repositoryRanksHighest() {
            List<CodeFile> files = List.of(
                    file("src/main/java/Utils.java", "java", "class Utils {}", 5, 1),
                    file("src/main/java/UserRepository.java", "java",
                            "interface UserRepository extends JpaRepository {}", 10, 3));

            List<CodeFile> ranked = agent.rankFiles(files, 15);

            assertThat(ranked.get(0).path()).isEqualTo("src/main/java/UserRepository.java");
        }

        @Test
        @DisplayName("synchronized keyword in patch ranks high for virtual thread pinning")
        void synchronizedInPatchRanksHigh() {
            List<CodeFile> files = List.of(
                    file("src/main/java/Utils.java", "java", "class Utils {}", 5, 1),
                    file("src/main/java/Processor.java", "java",
                            "synchronized void process() { httpClient.call(); }", 8, 2));

            List<CodeFile> ranked = agent.rankFiles(files, 15);

            assertThat(ranked.get(0).path()).isEqualTo("src/main/java/Processor.java");
        }

        @Test
        @DisplayName("service classes rank above controllers")
        void serviceRanksAboveController() {
            List<CodeFile> files = List.of(
                    file("src/main/java/UserController.java", "java", "class UserController {}", 5, 1),
                    file("src/main/java/OrderService.java", "java", "class OrderService {}", 5, 1));

            List<CodeFile> ranked = agent.rankFiles(files, 15);

            List<String> paths = ranked.stream().map(CodeFile::path).toList();
            assertThat(paths.indexOf("src/main/java/OrderService.java"))
                    .isLessThan(paths.indexOf("src/main/java/UserController.java"));
        }

        @Test
        @DisplayName("stream/loop keywords in patch boost ranking")
        void streamKeywordsBoostRanking() {
            List<CodeFile> files = List.of(
                    file("src/main/java/Helper.java", "java", "class Helper {}", 5, 1),
                    file("src/main/java/Processor.java", "java",
                            "items.stream().map(i -> transform(i)).collect(toList())", 8, 2));

            List<CodeFile> ranked = agent.rankFiles(files, 15);

            assertThat(ranked.get(0).path()).isEqualTo("src/main/java/Processor.java");
        }

        @Test
        @DisplayName("@Transactional in patch boosts ranking for Spring proxy detection")
        void transactionalInPatchBoostsRanking() {
            List<CodeFile> files = List.of(
                    file("src/main/java/Helper.java", "java", "class Helper {}", 5, 1),
                    file("src/main/java/OrderService.java", "java",
                            "@Transactional private void save() {}", 5, 1));

            List<CodeFile> ranked = agent.rankFiles(files, 15);

            assertThat(ranked.get(0).path()).isEqualTo("src/main/java/OrderService.java");
        }

        @Test
        @DisplayName("collection allocation in patch boosts ranking for GC pressure")
        void collectionAllocationBoostsRanking() {
            List<CodeFile> files = List.of(
                    file("src/main/java/Helper.java", "java", "class Helper {}", 5, 1),
                    file("src/main/java/Builder.java", "java",
                            "new ArrayList<>(); new HashMap<>();", 5, 1));

            List<CodeFile> ranked = agent.rankFiles(files, 15);

            assertThat(ranked.get(0).path()).isEqualTo("src/main/java/Builder.java");
        }

        @Test
        @DisplayName("RestTemplate/HttpClient in patch boosts ranking for I/O")
        void httpClientInPatchBoostsRanking() {
            List<CodeFile> files = List.of(
                    file("src/main/java/Helper.java", "java", "class Helper {}", 5, 1),
                    file("src/main/java/ApiClient.java", "java",
                            "new RestTemplate().getForObject(url, String.class)", 5, 1));

            List<CodeFile> ranked = agent.rankFiles(files, 15);

            assertThat(ranked.get(0).path()).isEqualTo("src/main/java/ApiClient.java");
        }

        @Test
        @DisplayName("output is capped at maxFiles")
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
        @DisplayName("empty input returns empty list")
        void emptyInputReturnsEmptyList() {
            List<CodeFile> ranked = agent.rankFiles(List.of(), 15);
            assertThat(ranked).isEmpty();
        }

        @Test
        @DisplayName("non-Java files are filtered out")
        void nonJavaFilesFilteredOut() {
            List<CodeFile> files = List.of(
                    file("README.md", "unknown", "# readme", 10, 2),
                    file("logo.png", "unknown", "", 0, 0),
                    file("src/main/java/App.java", "java", "class App {}", 5, 1));

            List<CodeFile> ranked = agent.rankFiles(files, 15);

            assertThat(ranked).hasSize(1);
            assertThat(ranked.get(0).path()).isEqualTo("src/main/java/App.java");
        }

        @Test
        @DisplayName("config files are included in ranking")
        void configFilesIncluded() {
            List<CodeFile> files = List.of(
                    file("application.yml", "yaml", "spring.cache.type: redis", 3, 0));

            List<CodeFile> ranked = agent.rankFiles(files, 15);

            assertThat(ranked).hasSize(1);
        }
    }

    @Nested
    @DisplayName("analyze()")
    class Analyze {

        @Test
        @DisplayName("returns empty result when no ranked files")
        void emptyWhenNoRankedFiles() {
            List<CodeFile> files = List.of(
                    file("README.md", "unknown", "# readme", 5, 1));

            AgentAnalysisResult result = agent.analyze(files, "abc123", "owner/repo", Optional.empty());

            assertThat(result.findingsJson()).contains("[]");
            assertThat(result.tierUsed()).isEqualTo(AiTier.LOCAL);
        }

        @Test
        @DisplayName("routes to CHEAP tier and returns AI response")
        void routesToCheapTier() {
            String aiResponse = """
                    {"findings":[{"severity":"HIGH","category":"VIRTUAL_THREADS",
                    "file":"Service.java","line":42,"title":"synchronized pins carrier thread",
                    "description":"synchronized block contains HTTP call",
                    "suggestion":"Use ReentrantLock","impact":"HIGH","effort":"LOW"}],
                    "summary":"1 performance issue found"}""";
            when(mockRouter.route(any(), eq(AiTier.CHEAP)))
                    .thenReturn(new AiModelRouter.AiResponse(aiResponse, AiTier.CHEAP, Duration.ofSeconds(1), true));

            List<CodeFile> files = List.of(
                    file("src/main/java/Service.java", "java",
                            "synchronized void process() { httpClient.call(); }", 10, 2));

            AgentAnalysisResult result = agent.analyze(files, "abc123", "owner/repo", Optional.empty());

            assertThat(result.findingsJson()).contains("VIRTUAL_THREADS");
            assertThat(result.tierUsed()).isEqualTo(AiTier.CHEAP);
            assertThat(result.summary()).isEqualTo("Performance analysis complete.");
        }

        @Test
        @DisplayName("prompt includes project guide when present")
        void promptIncludesGuide() {
            String aiResponse = "{\"findings\":[],\"summary\":\"No issues\"}";
            when(mockRouter.route(any(), eq(AiTier.CHEAP)))
                    .thenReturn(new AiModelRouter.AiResponse(aiResponse, AiTier.CHEAP, Duration.ofSeconds(1), true));

            var guide = Optional.of(new ProjectGuide("Use virtual threads everywhere.", false));
            List<CodeFile> files = List.of(
                    file("src/main/java/App.java", "java", "class App {}", 5, 1));

            // Should not throw — guide is passed through to PromptUtils.withGuide
            AgentAnalysisResult result = agent.analyze(files, "abc123", "owner/repo", guide);

            assertThat(result.findingsJson()).contains("No issues");
        }
    }

    private CodeFile file(String path, String language, String patch, int additions, int deletions) {
        return new CodeFile(path, language, patch, additions, deletions, null);
    }
}
