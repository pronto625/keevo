package com.keevo.identity.auth.domain.port.in;

import com.keevo.identity.auth.domain.model.SocialProvider;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Story 19.3b — the signed-in user's Google / Apple links (account settings): list, link, unlink.
 */
public interface SocialAccountUseCase {

    /** One linked provider identity, as shown to its owner (no provider id). */
    record LinkedSocialAccount(SocialProvider provider, Instant linkedAt) {
    }

    /** Link request: the provider's ID token, optional clear nonce and Apple authorization code. */
    record LinkCommand(UUID userId, SocialProvider provider, String token, String nonce, String authorizationCode,
                       String clientIp) {
        @Override
        public String toString() {
            return "LinkCommand{provider=" + provider + "}";
        }
    }

    /** The user's links, oldest first. */
    List<LinkedSocialAccount> list(UUID userId);

    /**
     * Links the proven identity to the user (idempotent when already linked to them).
     *
     * @return the user's links after linking
     * @throws com.keevo.shared.domain.exception.DomainException SOCIAL_TOKEN_INVALID, RATE_LIMIT_EXCEEDED,
     *         SOCIAL_ACCOUNT_LINKED_ELSEWHERE, SOCIAL_PROVIDER_ALREADY_LINKED
     */
    List<LinkedSocialAccount> link(LinkCommand command);

    /**
     * Unlinks the provider (no-op when not linked).
     *
     * @throws com.keevo.shared.domain.exception.DomainException LAST_SIGN_IN_METHOD when no other way in would remain
     */
    void unlink(UUID userId, SocialProvider provider);
}
