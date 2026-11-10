package com.keevo.identity.auth.application.service;

import com.keevo.identity.auth.domain.model.SocialIdentity;
import com.keevo.identity.auth.domain.port.out.SocialNonceRepository;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Locale;

/**
 * Story 19.3b — replay protection of social ID tokens. The app generates a random nonce, gives its SHA-256 (lowercase
 * hex) to Google / Apple, which put it in the token's {@code nonce} claim, and sends the clear nonce to Keevo.
 *
 * <ul>
 *   <li>clear nonce sent → the claim must equal its SHA-256 hex (case-insensitive);</li>
 *   <li>claim present, no clear nonce → refused (stripping the nonce never bypasses the check);</li>
 *   <li>neither → accepted unless {@code keevo.auth.social.require-nonce} (apps in stores send none yet).</li>
 * </ul>
 * A verified nonce is consumed once ({@code social_nonces}, kept until the token's expiry + {@link #KEEP_AFTER_EXPIRY}):
 * a captured token and its nonce never sign in twice. Every refusal is {@code SOCIAL_TOKEN_INVALID}; nonces are never
 * logged. One indexed insert per checked nonce.
 */
@Component
public class SocialNonceChecker {

    private static final Logger log = LoggerFactory.getLogger(SocialNonceChecker.class);

    /** Covers the verifier's clock skew on {@code exp}. */
    static final Duration KEEP_AFTER_EXPIRY = Duration.ofSeconds(60);
    /** Used when a token without {@code exp} reaches here (never from the verifier, which requires it). */
    static final Duration DEFAULT_LIFETIME = Duration.ofHours(1);

    private final SocialNonceRepository nonces;
    private final boolean requireNonce;
    private final Clock clock;

    public SocialNonceChecker(SocialNonceRepository nonces,
                              @Value("${keevo.auth.social.require-nonce:false}") boolean requireNonce,
                              Clock clock) {
        this.nonces = nonces;
        this.requireNonce = requireNonce;
        this.clock = clock;
    }

    /**
     * @param identity   the verified token's identity (carries the {@code nonce} claim)
     * @param clearNonce the nonce sent by the app, or null
     * @throws DomainException SOCIAL_TOKEN_INVALID when the rules above refuse the token
     */
    public void check(SocialIdentity identity, String clearNonce) {
        String claim = identity.nonce();
        String clear = clearNonce == null || clearNonce.isBlank() ? null : clearNonce;
        if (clear == null) {
            if (claim != null || requireNonce) {
                throw refused(identity, claim != null ? "nonce not sent" : "nonce required");
            }
            return;
        }
        if (claim == null || !sha256Hex(clear).equals(claim.toLowerCase(Locale.ROOT))) {
            throw refused(identity, "nonce mismatch");
        }
        Instant expiresAt = (identity.expiresAt() != null ? identity.expiresAt()
                : clock.instant().plus(DEFAULT_LIFETIME)).plus(KEEP_AFTER_EXPIRY);
        if (!nonces.consume(identity.provider(), claim.toLowerCase(Locale.ROOT), expiresAt)) {
            throw refused(identity, "nonce replayed");
        }
    }

    static String sha256Hex(String value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static DomainException refused(SocialIdentity identity, String reason) {
        log.warn("[social] {} token refused ({})", identity.provider(), reason);
        return new DomainException(ErrorCode.SOCIAL_TOKEN_INVALID);
    }
}
