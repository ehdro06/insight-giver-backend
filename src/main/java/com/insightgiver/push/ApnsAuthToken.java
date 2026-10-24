package com.insightgiver.push;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;

/**
 * The provider token APNs authenticates requests with: an ES256-signed JWT naming the key and the
 * team. APNs rejects tokens older than an hour, and also new tokens issued more often than every 20
 * minutes, so each one is reused for 50.
 */
final class ApnsAuthToken {

    private static final Duration REFRESH_AFTER = Duration.ofMinutes(50);
    private static final Base64.Encoder BASE64_URL = Base64.getUrlEncoder().withoutPadding();

    private final String teamId;
    private final String keyId;
    private final PrivateKey key;
    private final Clock clock;

    private String token;
    private Instant issuedAt;

    ApnsAuthToken(String teamId, String keyId, PrivateKey key, Clock clock) {
        this.teamId = teamId;
        this.keyId = keyId;
        this.key = key;
        this.clock = clock;
    }

    synchronized String current() {
        Instant now = clock.instant();
        if (token == null || !now.isBefore(issuedAt.plus(REFRESH_AFTER))) {
            token = sign(now);
            issuedAt = now;
        }
        return token;
    }

    /** Forces a new token on the next request, after APNs reported this one expired. */
    synchronized void invalidate() {
        token = null;
    }

    private String sign(Instant now) {
        // Team and key ids are validated as [A-Z0-9]{10}, so they need no JSON escaping.
        String header = encode("{\"alg\":\"ES256\",\"kid\":\"" + keyId + "\"}");
        String claims = encode("{\"iss\":\"" + teamId + "\",\"iat\":" + now.getEpochSecond() + "}");
        String unsigned = header + "." + claims;
        try {
            // JWS wants the raw r||s signature, not the DER encoding Java produces by default.
            Signature signature = Signature.getInstance("SHA256withECDSAinP1363Format");
            signature.initSign(key);
            signature.update(unsigned.getBytes(StandardCharsets.US_ASCII));
            return unsigned + "." + BASE64_URL.encodeToString(signature.sign());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Could not sign the APNs provider token", e);
        }
    }

    private static String encode(String json) {
        return BASE64_URL.encodeToString(json.getBytes(StandardCharsets.UTF_8));
    }

    /** Reads the PKCS#8 PEM (.p8) key the Apple Developer portal issues for APNs. */
    static PrivateKey readPrivateKey(String pem) {
        String base64 = pem.replaceAll("-----(BEGIN|END) PRIVATE KEY-----", "").replaceAll("\\s", "");
        try {
            return KeyFactory.getInstance("EC").generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(base64)));
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new IllegalStateException("The APNs private key is not a valid .p8 key", e);
        }
    }
}
