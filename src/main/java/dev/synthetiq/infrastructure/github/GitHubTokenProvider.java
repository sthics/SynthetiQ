package dev.synthetiq.infrastructure.github;

import dev.synthetiq.config.GitHubProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages GitHub App installation tokens with in-memory caching.
 * TODO: Implement JWT signing with the App's private key.
 */
@Component
public class GitHubTokenProvider {
    private static final Logger log = LoggerFactory.getLogger(GitHubTokenProvider.class);
    private final GitHubProperties properties;
    private final Map<Long, CachedToken> cache = new ConcurrentHashMap<>();

    public GitHubTokenProvider(GitHubProperties properties) { this.properties = properties; }

    public String getInstallationToken(long installationId) {
        CachedToken cached = cache.get(installationId);
        if (cached != null && cached.expiresAt().isAfter(Instant.now().plusSeconds(300))) {
            return cached.token();
        }
        // TODO: Generate JWT, exchange for installation token
        throw new UnsupportedOperationException("JWT generation not yet implemented");
    }

    private record CachedToken(String token, Instant expiresAt) {}
}
