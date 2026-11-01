package com.keevo.shared.infrastructure.security;

import io.jsonwebtoken.Jwts;

import java.security.KeyFactory;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.Map;
import java.util.UUID;

/** Test-only RSA keys (src/test/resources/keys) and a raw token builder for filter tests. */
public final class TestJwtKeys {

    private TestJwtKeys() {
    }

    public static RSAPrivateKey privateKey() {
        try {
            byte[] decoded = Base64.getDecoder().decode(pem("/keys/private_key.pem",
                    "-----BEGIN PRIVATE KEY-----", "-----END PRIVATE KEY-----"));
            return (RSAPrivateKey) KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(decoded));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public static RSAPublicKey publicKey() {
        try {
            byte[] decoded = Base64.getDecoder().decode(pem("/keys/public_key.pem",
                    "-----BEGIN PUBLIC KEY-----", "-----END PUBLIC KEY-----"));
            return (RSAPublicKey) KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(decoded));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public static JwtTokenProvider provider(JwtProperties properties) {
        return new JwtTokenProvider(privateKey(), publicKey(), properties);
    }

    /** Signs an access token with arbitrary claims and issue time (valid for one hour after {@code iat}). */
    public static String token(UUID userId, String tenantId, Instant iat, Map<String, Object> extraClaims) {
        var builder = Jwts.builder()
                .subject(userId.toString())
                .claim("tenantId", tenantId)
                .issuedAt(Date.from(iat))
                .expiration(Date.from(iat.plusSeconds(3600)))
                .signWith(privateKey(), Jwts.SIG.RS256);
        extraClaims.forEach(builder::claim);
        return builder.compact();
    }

    private static String pem(String resource, String begin, String end) throws Exception {
        try (var stream = TestJwtKeys.class.getResourceAsStream(resource)) {
            return new String(stream.readAllBytes()).replace(begin, "").replace(end, "").replaceAll("\\s", "");
        }
    }
}
