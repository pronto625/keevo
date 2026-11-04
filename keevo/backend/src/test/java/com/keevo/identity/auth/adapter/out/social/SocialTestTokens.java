package com.keevo.identity.auth.adapter.out.social;

import io.jsonwebtoken.JwtBuilder;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Jwks;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;

/**
 * Story 19.3 — test identity provider: a locally generated RSA key pair, its JWKS document and RS256 ID tokens
 * signed with it (no network).
 */
public final class SocialTestTokens {

    private final String kid;
    private final KeyPair keyPair;

    public SocialTestTokens(String kid) {
        this.kid = kid;
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            this.keyPair = generator.generateKeyPair();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public String kid() {
        return kid;
    }

    /** JWKS document holding this provider's public key. */
    public String jwks() {
        return "{\"keys\":[" + jwk() + "]}";
    }

    /** One JWK (to compose multi-key sets). */
    public String jwk() {
        return Jwks.json(Jwks.builder().key((RSAPublicKey) keyPair.getPublic()).id(kid).build());
    }

    /** RS256 token with this key; {@code claims} may hold any extra claim (email, email_verified, names). */
    public String token(String issuer, String audience, String subject, Instant expiresAt, Map<String, ?> claims) {
        return token(issuer, audience == null ? List.of() : List.of(audience), subject, expiresAt, claims, "RS256");
    }

    /**
     * Token with several audiences, an optional expiry (null = no {@code exp}) and an algorithm: {@code RS256},
     * {@code RS512} (this RSA key), {@code HS256} (a random HMAC key) or {@code none} (unsigned).
     */
    public String token(String issuer, List<String> audiences, String subject, Instant expiresAt,
                        Map<String, ?> claims, String algorithm) {
        JwtBuilder builder = Jwts.builder()
                .header().keyId(kid).and()
                .issuer(issuer)
                .subject(subject)
                .claims(claims);
        if (expiresAt != null) {
            builder.issuedAt(Date.from(expiresAt.minusSeconds(3600))).expiration(Date.from(expiresAt));
        }
        if (!audiences.isEmpty()) {
            builder.audience().add(audiences).and();
        }
        return switch (algorithm) {
            case "RS256" -> builder.signWith(keyPair.getPrivate(), Jwts.SIG.RS256).compact();
            case "RS512" -> builder.signWith(keyPair.getPrivate(), Jwts.SIG.RS512).compact();
            case "HS256" -> builder.signWith(Jwts.SIG.HS256.key().build(), Jwts.SIG.HS256).compact();
            case "none" -> builder.compact();
            default -> throw new IllegalArgumentException(algorithm);
        };
    }
}
