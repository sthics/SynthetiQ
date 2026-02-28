package dev.synthetiq.infrastructure.github;

import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;
import dev.synthetiq.domain.valueobject.ProjectGuide;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

import java.lang.reflect.Field;
import java.util.Optional;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@WireMockTest
class GitHubApiClientGuideTest {

    private GitHubApiClient client;
    private GitHubTokenProvider tokenProvider;

    @BeforeEach
    void setUp(WireMockRuntimeInfo wmInfo) throws Exception {
        tokenProvider = mock(GitHubTokenProvider.class);
        when(tokenProvider.getInstallationToken(1L)).thenReturn("test-token");

        // Build the client normally (constructor hardcodes baseUrl to api.github.com)
        client = new GitHubApiClient(WebClient.builder(), tokenProvider);

        // Replace the webClient field with one pointing at WireMock
        WebClient wireMockClient = WebClient.builder()
                .baseUrl(wmInfo.getHttpBaseUrl())
                .build();
        Field webClientField = GitHubApiClient.class.getDeclaredField("webClient");
        webClientField.setAccessible(true);
        webClientField.set(client, wireMockClient);
    }

    @Test
    @DisplayName("returns guide when SYNTHETIQ.md exists")
    void returnsGuideWhenFileExists() {
        stubFor(get(urlPathEqualTo("/repos/owner/repo/contents/SYNTHETIQ.md"))
                .willReturn(ok("# Project Rules\n- No field injection")));

        Optional<ProjectGuide> guide = client.getProjectGuide("owner/repo", 1L);

        assertThat(guide).isPresent();
        assertThat(guide.get().content()).isEqualTo("# Project Rules\n- No field injection");
        assertThat(guide.get().truncated()).isFalse();
    }

    @Test
    @DisplayName("returns empty when SYNTHETIQ.md does not exist (404)")
    void returnsEmptyOn404() {
        stubFor(get(urlPathEqualTo("/repos/owner/repo/contents/SYNTHETIQ.md"))
                .willReturn(notFound()));

        Optional<ProjectGuide> guide = client.getProjectGuide("owner/repo", 1L);

        assertThat(guide).isEmpty();
    }

    @Test
    @DisplayName("returns empty on server error (500)")
    void returnsEmptyOnServerError() {
        stubFor(get(urlPathEqualTo("/repos/owner/repo/contents/SYNTHETIQ.md"))
                .willReturn(serverError()));

        Optional<ProjectGuide> guide = client.getProjectGuide("owner/repo", 1L);

        assertThat(guide).isEmpty();
    }
}
