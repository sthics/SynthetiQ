package dev.synthetiq.infrastructure.github;

import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;
import dev.synthetiq.agent.orchestrator.InlineComment;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

import java.lang.reflect.Field;
import java.util.List;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@WireMockTest
class GitHubApiClientInlineCommentTest {

    private GitHubApiClient client;

    @BeforeEach
    void setUp(WireMockRuntimeInfo wmInfo) throws Exception {
        GitHubTokenProvider tokenProvider = mock(GitHubTokenProvider.class);
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
    void createReview_withInlineComments_sendsCommentsArray() throws Exception {
        stubFor(post(urlEqualTo("/repos/owner/repo/pulls/1/reviews"))
                .willReturn(aResponse().withStatus(200)));

        List<InlineComment> inlineComments = List.of(
                new InlineComment("src/Main.java", 10, ":red_circle: **CRITICAL**\n\nSQL injection"),
                new InlineComment("src/App.java", 25, ":orange_circle: **HIGH**\n\nMissing null check"));

        client.createReview("owner/repo", 1, 1L, "Summary body", "REQUEST_CHANGES", inlineComments);

        verify(postRequestedFor(urlEqualTo("/repos/owner/repo/pulls/1/reviews"))
                .withHeader("Authorization", equalTo("Bearer test-token"))
                .withRequestBody(matchingJsonPath("$.body", equalTo("Summary body")))
                .withRequestBody(matchingJsonPath("$.event", equalTo("REQUEST_CHANGES")))
                .withRequestBody(matchingJsonPath("$.comments[0].path", equalTo("src/Main.java")))
                .withRequestBody(matchingJsonPath("$.comments[0].line", equalTo("10")))
                .withRequestBody(matchingJsonPath("$.comments[0].body", containing("SQL injection"))));
    }

    @Test
    void createReview_withEmptyInlineComments_sendsNoCommentsField() throws Exception {
        stubFor(post(urlEqualTo("/repos/owner/repo/pulls/1/reviews"))
                .willReturn(aResponse().withStatus(200)));

        client.createReview("owner/repo", 1, 1L, "Summary body", "APPROVE", List.of());

        verify(postRequestedFor(urlEqualTo("/repos/owner/repo/pulls/1/reviews"))
                .withRequestBody(matchingJsonPath("$.body", equalTo("Summary body")))
                .withRequestBody(matchingJsonPath("$.event", equalTo("APPROVE")))
                .withRequestBody(matchingJsonPath("$.comments", absent())));
    }
}
