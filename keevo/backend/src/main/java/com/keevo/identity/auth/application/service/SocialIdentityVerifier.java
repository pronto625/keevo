package com.keevo.identity.auth.application.service;

import com.keevo.identity.auth.domain.model.SocialIdentity;
import com.keevo.identity.auth.domain.model.SocialProvider;
import com.keevo.identity.auth.domain.port.out.SocialTokenVerifier;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Story 19.3 / 19.3b — proves a social identity from a raw ID token, for sign-in and for linking: per-IP limit on
 * refused tokens → the provider's {@link SocialTokenVerifier} (Strategy; may fetch the JWKS, so callers run it outside
 * any transaction) → nonce rules ({@link SocialNonceChecker}). Only refusals count toward the limit.
 */
@Component
public class SocialIdentityVerifier {

    private final Map<SocialProvider, SocialTokenVerifier> verifiers = new EnumMap<>(SocialProvider.class);
    private final SocialSignInRateLimiter rateLimiter;
    private final SocialNonceChecker nonceChecker;

    public SocialIdentityVerifier(List<SocialTokenVerifier> verifiers, SocialSignInRateLimiter rateLimiter,
                                  SocialNonceChecker nonceChecker) {
        for (SocialTokenVerifier v : verifiers) {
            if (this.verifiers.putIfAbsent(v.provider(), v) != null) {
                throw new IllegalStateException("Two social token verifiers for " + v.provider());
            }
        }
        this.rateLimiter = rateLimiter;
        this.nonceChecker = nonceChecker;
    }

    /**
     * @param clientIp caller address for the failure limit (null = not limited)
     * @throws DomainException RATE_LIMIT_EXCEEDED, or SOCIAL_TOKEN_INVALID for any refused token or nonce
     */
    public SocialIdentity verify(SocialProvider provider, String token, String nonce, String clientIp) {
        rateLimiter.checkAllowed(provider, clientIp);
        SocialTokenVerifier verifier = verifiers.get(provider);
        if (verifier == null) {
            throw new DomainException(ErrorCode.SOCIAL_TOKEN_INVALID);
        }
        try {
            SocialIdentity identity = verifier.verify(token);
            nonceChecker.check(identity, nonce);
            return identity;
        } catch (DomainException e) {
            rateLimiter.recordFailure(provider, clientIp);
            throw e;
        }
    }
}
