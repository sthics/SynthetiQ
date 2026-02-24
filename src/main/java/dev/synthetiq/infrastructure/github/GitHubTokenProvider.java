package dev.synthetiq.infrastructure.github;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import dev.synthetiq.config.GitHubProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.security.KeyFactory;
import java.security.interfaces.RSAPrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.RSAPrivateCrtKeySpec;
import java.math.BigInteger;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages GitHub App authentication: generates JWTs signed with the App's private key,
 * exchanges them for short-lived installation tokens, and caches those tokens.
 */
@Component
public class GitHubTokenProvider {
    private static final Logger log = LoggerFactory.getLogger(GitHubTokenProvider.class);

    private final GitHubProperties properties;
    private final WebClient webClient;
    private final RSAPrivateKey privateKey;
    private final Map<Long, CachedToken> cache = new ConcurrentHashMap<>();

    public GitHubTokenProvider(GitHubProperties properties, WebClient.Builder builder) {
        this.properties = properties;
        this.webClient = builder.baseUrl("https://api.github.com").build();
        this.privateKey = parsePrivateKey(properties.privateKey());
    }

    public String getInstallationToken(long installationId) {
        CachedToken cached = cache.get(installationId);
        if (cached != null && cached.expiresAt().isAfter(Instant.now().plusSeconds(300))) {
            log.debug("Using cached installation token for installation {}", installationId);
            return cached.token();
        }

        String jwt = generateJwt();
        log.debug("Generated JWT for app {}, exchanging for installation token", properties.appId());

        @SuppressWarnings("unchecked")
        Map<String, Object> response = webClient.post()
                .uri("/app/installations/{installationId}/access_tokens", installationId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + jwt)
                .header(HttpHeaders.ACCEPT, "application/vnd.github.v3+json")
                .retrieve()
                .bodyToMono(Map.class)
                .block();

        if (response == null || !response.containsKey("token")) {
            throw new IllegalStateException("Failed to obtain installation token for installation " + installationId);
        }

        String token = (String) response.get("token");
        String expiresAtStr = (String) response.get("expires_at");
        Instant expiresAt = Instant.parse(expiresAtStr);

        cache.put(installationId, new CachedToken(token, expiresAt));
        log.info("Obtained installation token for installation {} (expires {})", installationId, expiresAt);
        return token;
    }

    private String generateJwt() {
        try {
            Instant now = Instant.now();
            JWTClaimsSet claims = new JWTClaimsSet.Builder()
                    .issuer(String.valueOf(properties.appId()))
                    .issueTime(Date.from(now.minusSeconds(60)))  // clock skew buffer
                    .expirationTime(Date.from(now.plusSeconds(600)))  // 10 min max
                    .build();

            SignedJWT signedJwt = new SignedJWT(new JWSHeader(JWSAlgorithm.RS256), claims);
            signedJwt.sign(new RSASSASigner(privateKey));
            return signedJwt.serialize();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to generate JWT for GitHub App", e);
        }
    }

    private static RSAPrivateKey parsePrivateKey(String pem) {
        if (pem == null || pem.isBlank()) {
            log.warn("GitHub App private key not configured — token generation will fail at runtime");
            return null;
        }
        try {
            boolean pkcs1 = pem.contains("BEGIN RSA PRIVATE KEY");
            String stripped = pem
                    .replace("-----BEGIN RSA PRIVATE KEY-----", "")
                    .replace("-----END RSA PRIVATE KEY-----", "")
                    .replace("-----BEGIN PRIVATE KEY-----", "")
                    .replace("-----END PRIVATE KEY-----", "")
                    .replaceAll("\\s", "");
            byte[] decoded = Base64.getDecoder().decode(stripped);
            KeyFactory kf = KeyFactory.getInstance("RSA");

            if (pkcs1) {
                // GitHub App keys are PKCS#1 (BEGIN RSA PRIVATE KEY) — parse DER manually
                return (RSAPrivateKey) kf.generatePrivate(parsePkcs1PrivateKey(decoded));
            } else {
                // PKCS#8 (BEGIN PRIVATE KEY) — Java natively supports this
                return (RSAPrivateKey) kf.generatePrivate(new PKCS8EncodedKeySpec(decoded));
            }
        } catch (Exception e) {
            throw new IllegalStateException("Failed to parse GitHub App private key from PEM content", e);
        }
    }

    /**
     * Parses a PKCS#1 RSAPrivateKey DER structure into an RSAPrivateCrtKeySpec.
     * ASN.1: RSAPrivateKey ::= SEQUENCE { version, n, e, d, p, q, dP, dQ, qInv }
     */
    private static RSAPrivateCrtKeySpec parsePkcs1PrivateKey(byte[] der) {
        int[] offset = {0};
        readTag(der, offset);      // SEQUENCE tag
        readLength(der, offset);   // SEQUENCE length
        readBigInteger(der, offset); // version (discard)
        BigInteger n    = readBigInteger(der, offset);
        BigInteger e    = readBigInteger(der, offset);
        BigInteger d    = readBigInteger(der, offset);
        BigInteger p    = readBigInteger(der, offset);
        BigInteger q    = readBigInteger(der, offset);
        BigInteger dP   = readBigInteger(der, offset);
        BigInteger dQ   = readBigInteger(der, offset);
        BigInteger qInv = readBigInteger(der, offset);
        return new RSAPrivateCrtKeySpec(n, e, d, p, q, dP, dQ, qInv);
    }

    private static void readTag(byte[] der, int[] offset) {
        offset[0]++;
    }

    private static int readLength(byte[] der, int[] offset) {
        int b = der[offset[0]++] & 0xFF;
        if (b < 0x80) return b;
        int numBytes = b & 0x7F;
        int length = 0;
        for (int i = 0; i < numBytes; i++) {
            length = (length << 8) | (der[offset[0]++] & 0xFF);
        }
        return length;
    }

    private static BigInteger readBigInteger(byte[] der, int[] offset) {
        readTag(der, offset); // INTEGER tag (0x02)
        int length = readLength(der, offset);
        byte[] value = new byte[length];
        System.arraycopy(der, offset[0], value, 0, length);
        offset[0] += length;
        return new BigInteger(value);
    }

    private record CachedToken(String token, Instant expiresAt) {}
}
