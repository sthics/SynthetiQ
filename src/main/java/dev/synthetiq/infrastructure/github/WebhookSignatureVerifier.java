package dev.synthetiq.infrastructure.github;

import dev.synthetiq.config.GitHubProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

/**
 * HMAC-SHA256 verification for GitHub webhooks.
 * Uses constant-time comparison to prevent timing attacks.
 */
@Component
public class WebhookSignatureVerifier {
    private static final Logger log = LoggerFactory.getLogger(WebhookSignatureVerifier.class);
    private final GitHubProperties properties;

    public WebhookSignatureVerifier(GitHubProperties properties) { this.properties = properties; }

    public boolean isValid(byte[] payload, String signature) {
        if (signature == null || !signature.startsWith("sha256=")) return false;
        String secret = properties.webhookSecret();
        if (secret == null || secret.isBlank()) return false;
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            String expected = "sha256=" + HexFormat.of().formatHex(mac.doFinal(payload));
            return MessageDigest.isEqual(
                    expected.getBytes(StandardCharsets.UTF_8),
                    signature.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) { log.error("HMAC failed", e); return false; }
    }
}
