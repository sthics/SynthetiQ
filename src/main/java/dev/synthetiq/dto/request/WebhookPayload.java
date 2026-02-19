package dev.synthetiq.dto.request;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record WebhookPayload(
        String action,
        @JsonProperty("pull_request") PullRequest pullRequest,
        Repository repository,
        Installation installation
) {
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PullRequest(int number, Head head, Base base, String title,
                               @JsonProperty("changed_files") int changedFiles,
                               int additions, int deletions) {}
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Head(String sha, String ref) {}
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Base(String ref) {}
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Repository(@JsonProperty("full_name") String fullName,
                              @JsonProperty("private") boolean isPrivate) {}
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Installation(long id) {}

    public boolean isActionable() {
        return "opened".equals(action) || "synchronize".equals(action);
    }
}
