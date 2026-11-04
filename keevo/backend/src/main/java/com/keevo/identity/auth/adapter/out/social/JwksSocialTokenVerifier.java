package com.keevo.identity.auth.adapter.out.social;

import com.keevo.identity.auth.domain.model.SocialIdentity;
import com.keevo.identity.auth.domain.model.SocialProvider;
import com.keevo.identity.auth.domain.port.out.SocialTokenVerifier;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwsHeader;
import io.jsonwebtoken.JwtParser;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.LocatorAdapter;
import io.jsonwebtoken.security.Jwk;
import io.jsonwebtoken.security.JwkSet;
import io.jsonwebtoken.security.Jwks;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.security.Key;
import java.security.PublicKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Story 19.3 — Strategy implementation shared by Google and Apple: verifies an ID token locally against the
 * provider's JWKS, parameterised by issuers, JWKS URL, audiences and the provider's email rule.
 *
 * <p>Checks: RS256 signature with the key whose {@code kid} matches; {@code iss} among {@code issuers}; {@code aud}
 * among the configured audiences (empty list = provider refused, no network call); {@code exp} present and in the
 * future ({@value #CLOCK_SKEW_SECONDS} s skew); {@code sub} non-blank, at most {@value #SUBJECT_MAX} chars; Google
 * with several audiences: {@code azp} among the configured ones. Email: Google refuses a token whose email is not
 * verified; Apple keeps the email only when verified ({@code email_verified} boolean or string). Any failure →
 * {@code SOCIAL_TOKEN_INVALID}, logged with the provider and the failure kind only (never the token, email or
 * subject).
 *
 * <p>Keys: cache-aside, in memory, for the response's {@code Cache-Control max-age} (default 1 h, kept between
 * {@link #MIN_TTL} and {@link #MAX_TTL}). An unknown {@code kid} (key rotation) forces one refetch at most every
 * {@link #FORCED_REFRESH_INTERVAL}. A failed fetch, or a document without any RSA key, keeps the previous keys (up to
 * {@link #STALE_GRACE} past their original expiry, then none) and is retried after {@link #FORCED_REFRESH_INTERVAL}. O(1) per sign-in when cached.
 */
public class JwksSocialTokenVerifier implements SocialTokenVerifier {

    private static final Logger log = LoggerFactory.getLogger(JwksSocialTokenVerifier.class);

    static final long CLOCK_SKEW_SECONDS = 60;
    static final Duration DEFAULT_TTL = Duration.ofHours(1);
    static final Duration MIN_TTL = Duration.ofMinutes(1);
    static final Duration MAX_TTL = Duration.ofHours(24);
    static final Duration FORCED_REFRESH_INTERVAL = Duration.ofSeconds(60);
    /** How long past their own expiry previous keys stay trusted while the JWKS cannot be refreshed. */
    static final Duration STALE_GRACE = Duration.ofHours(24);
    /** {@code user_authenticators.subject} is varchar(255). */
    static final int SUBJECT_MAX = 255;

    /** How the provider's email claim is treated. */
    public enum EmailRule {
        /** Google: an email whose {@code email_verified} is not true refuses the token. */
        REJECT_UNVERIFIED,
        /** Apple: an unverified email is ignored (the token still signs in by subject). */
        IGNORE_UNVERIFIED
    }

    private final SocialProvider provider;
    private final Set<String> issuers;
    private final String jwksUri;
    private final Set<String> audiences;
    private final EmailRule emailRule;
    private final JwksClient jwksClient;
    private final Clock clock;
    private final JwtParser parser;

    private final Object refreshLock = new Object();
    private volatile KeySet keys = KeySet.EMPTY;
    /** Last JWKS fetch attempt (success or failure): throttles refetches on an unknown kid. */
    private volatile Instant lastFetch = Instant.EPOCH;

    public JwksSocialTokenVerifier(SocialProvider provider, Set<String> issuers, String jwksUri,
                                   List<String> audiences, EmailRule emailRule, JwksClient jwksClient, Clock clock) {
        this.provider = provider;
        this.issuers = Set.copyOf(issuers);
        this.jwksUri = jwksUri;
        this.audiences = audiences.stream().map(String::strip).filter(a -> !a.isEmpty())
                .collect(Collectors.toUnmodifiableSet());
        this.emailRule = emailRule;
        this.jwksClient = jwksClient;
        this.clock = clock;
        this.parser = Jwts.parser()
                .keyLocator(new LocatorAdapter<Key>() {
                    @Override
                    protected Key locate(JwsHeader header) {
                        return signingKey(header);
                    }
                })
                .clock(() -> Date.from(this.clock.instant()))
                .clockSkewSeconds(CLOCK_SKEW_SECONDS)
                .build();
    }

    @Override
    public SocialProvider provider() {
        return provider;
    }

    /** True when at least one audience is configured (otherwise every token is refused). */
    public boolean isConfigured() {
        return !audiences.isEmpty();
    }

    @Override
    public SocialIdentity verify(String token) {
        if (!isConfigured()) {
            throw refused("no audience configured");
        }
        if (token == null || token.isBlank()) {
            throw refused("empty token");
        }
        Claims claims;
        try {
            claims = parser.parseSignedClaims(token.strip()).getPayload();
        } catch (DomainException e) {
            throw e;
        } catch (RuntimeException e) {
            throw refused(e.getClass().getSimpleName());
        }
        if (claims.getIssuer() == null || !issuers.contains(claims.getIssuer())) {
            throw refused("issuer");
        }
        Set<String> aud = claims.getAudience();
        if (aud == null || aud.stream().noneMatch(audiences::contains)) {
            throw refused("audience");
        }
        // Google (OIDC): with several audiences, the authorized party must be one of our clients.
        if (provider == SocialProvider.GOOGLE && aud.size() > 1) {
            String azp = text(claims.get("azp"));
            if (azp == null || !audiences.contains(azp)) {
                throw refused("authorized party");
            }
        }
        if (claims.getExpiration() == null) {
            throw refused("no expiry");
        }
        String subject = claims.getSubject();
        if (subject == null || subject.isBlank() || subject.strip().length() > SUBJECT_MAX) {
            throw refused("subject");
        }
        String email = text(claims.get("email"));
        boolean emailVerified = isTrue(claims.get("email_verified"));
        if (email != null && !emailVerified && emailRule == EmailRule.REJECT_UNVERIFIED) {
            throw refused("email not verified");
        }
        return new SocialIdentity(provider, subject.strip(), email, emailVerified,
                text(claims.get("given_name")), text(claims.get("family_name")));
    }

    // ── Keys ─────────────────────────────────────────────────────────────────

    private Key signingKey(JwsHeader header) {
        if (!"RS256".equals(header.getAlgorithm())) {
            throw refused("algorithm");
        }
        String kid = header.getKeyId();
        if (kid == null || kid.isBlank()) {
            throw refused("no kid");
        }
        Instant now = clock.instant();
        KeySet current = keys;
        if (current.isExpired(now)) {
            current = refresh(false, now);
        }
        PublicKey key = current.get(kid);
        if (key == null) {
            key = refresh(true, now).get(kid);
        }
        if (key == null) {
            throw refused("unknown kid");
        }
        return key;
    }

    /**
     * @param unknownKid true for a key-rotation refetch (throttled to one per {@link #FORCED_REFRESH_INTERVAL}),
     *                   false for an expired cache
     */
    private KeySet refresh(boolean unknownKid, Instant now) {
        synchronized (refreshLock) {
            KeySet current = keys;
            boolean throttled = now.isBefore(lastFetch.plus(FORCED_REFRESH_INTERVAL));
            // Expired cache: another thread may have refreshed it meanwhile. Unknown kid: at most one fetch a minute.
            if (unknownKid ? throttled : !current.isExpired(now)) {
                return current;
            }
            lastFetch = now;
            String failure;
            try {
                JwksClient.JwksDocument document = jwksClient.fetch(jwksUri);
                Map<String, PublicKey> fetched = parse(document.json());
                if (!fetched.isEmpty()) {
                    Instant expiresAt = now.plus(ttl(document.maxAge()));
                    KeySet fresh = new KeySet(fetched, expiresAt, expiresAt.plus(STALE_GRACE));
                    keys = fresh;
                    return fresh;
                }
                failure = "no RSA key";
            } catch (RuntimeException e) {
                failure = e.getClass().getSimpleName();
            }
            // Previous keys stay trusted up to STALE_GRACE past their original expiry, then none.
            KeySet kept = now.isBefore(current.staleUntil())
                    ? new KeySet(current.byKid(), now.plus(FORCED_REFRESH_INTERVAL), current.staleUntil())
                    : new KeySet(Map.of(), now.plus(FORCED_REFRESH_INTERVAL), Instant.EPOCH);
            log.warn("[social] {} keys unavailable ({}) — {}", provider, failure,
                    kept.byKid().isEmpty() ? "no key trusted" : "previous keys kept");
            keys = kept;
            return kept;
        }
    }

    private static Map<String, PublicKey> parse(String json) {
        JwkSet set = Jwks.setParser().ignoreUnsupported(true).build().parse(json);
        Map<String, PublicKey> byKid = new HashMap<>();
        for (Jwk<?> jwk : set.getKeys()) {
            Key key = jwk.toKey();
            if (jwk.getId() != null && key instanceof RSAPublicKey rsa) {
                byKid.put(jwk.getId(), rsa);
            }
        }
        return Map.copyOf(byKid);
    }

    private static Duration ttl(Duration maxAge) {
        if (maxAge == null) {
            return DEFAULT_TTL;
        }
        if (maxAge.compareTo(MIN_TTL) < 0) {
            return MIN_TTL;
        }
        return maxAge.compareTo(MAX_TTL) > 0 ? MAX_TTL : maxAge;
    }

    /** Immutable snapshot of the cached keys and their expiry. */
    private record KeySet(Map<String, PublicKey> byKid, Instant expiresAt, Instant staleUntil) {
        static final KeySet EMPTY = new KeySet(Map.of(), Instant.EPOCH, Instant.EPOCH);

        boolean isExpired(Instant now) {
            return !now.isBefore(expiresAt);
        }

        PublicKey get(String kid) {
            return byKid.get(kid);
        }
    }

    // ── Claims ───────────────────────────────────────────────────────────────

    private static String text(Object value) {
        if (!(value instanceof String s)) {
            return null;
        }
        String stripped = s.strip();
        return stripped.isEmpty() ? null : stripped;
    }

    /** {@code email_verified} is a boolean (Google) or the string "true" (Apple). */
    private static boolean isTrue(Object value) {
        return Boolean.TRUE.equals(value) || (value instanceof String s && "true".equalsIgnoreCase(s.strip()));
    }

    private DomainException refused(String reason) {
        log.warn("[social] {} token refused ({})", provider, reason);
        return new DomainException(ErrorCode.SOCIAL_TOKEN_INVALID);
    }
}
