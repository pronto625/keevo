package com.keevo.identity.auth.application.service;

import com.keevo.identity.auth.domain.model.SocialIdentity;
import com.keevo.identity.auth.domain.model.SocialProvider;
import com.keevo.identity.auth.domain.port.out.AppleRevocationQueue;
import com.keevo.identity.auth.domain.port.out.AppleTokenClient;
import com.keevo.identity.auth.domain.port.out.UserAuthenticatorRepository;
import com.keevo.shared.infrastructure.security.crypto.SecretCipher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;

/**
 * Story 19.3b — keeps what is needed to revoke a Sign in with Apple link later (App Store account-deletion rule):
 * the app's one-time authorization code is exchanged for an Apple refresh token, stored encrypted
 * ({@link SecretCipher}, context = authenticator row id) on the authenticator with the client id it was issued for.
 *
 * <p>Called after the sign-in / link transaction committed, asynchronously (the exchange is a network call that must
 * not slow the sign-in down). Best effort: disabled
 * adapter, no code, no cipher key, Apple refusal, a grant for another Apple user or any failure → nothing stored, a
 * log line with the kind only (never the code, token or subject), and the sign-in is not affected.
 *
 * <p>A link removed while the code was being exchanged (unlink, account deletion) leaves no row to store on: the new
 * token goes straight to the revocation outbox, so no live Apple token is ever forgotten. Runs on its own executor
 * ({@code appleTokenExecutor}).
 *
 * <p>Cost: 1 network call + 1 lookup + 1 UPDATE, both on the {@code (provider, subject)} unique index / primary key.
 */
@Component
public class AppleTokenStore {

    private static final Logger log = LoggerFactory.getLogger(AppleTokenStore.class);

    /** Apple's authorization codes are short; anything longer is not one. */
    static final int CODE_MAX = 2048;

    private final AppleTokenClient appleTokenClient;
    private final UserAuthenticatorRepository authenticators;
    private final SecretCipher cipher;
    private final AppleRevocationQueue revocationQueue;

    public AppleTokenStore(AppleTokenClient appleTokenClient, UserAuthenticatorRepository authenticators,
                           SecretCipher cipher, AppleRevocationQueue revocationQueue) {
        this.revocationQueue = revocationQueue;
        this.appleTokenClient = appleTokenClient;
        this.authenticators = authenticators;
        this.cipher = cipher;
    }

    /**
     * @param identity          verified Apple identity (subject + audience = client id)
     * @param authorizationCode the code sent by the app, or null
     */
    @Async("appleTokenExecutor")
    public void storeIfPossible(SocialIdentity identity, String authorizationCode) {
        if (identity.provider() != SocialProvider.APPLE || authorizationCode == null || authorizationCode.isBlank()
                || authorizationCode.length() > CODE_MAX || !appleTokenClient.enabled()) {
            return;
        }
        if (!cipher.isAvailable() || identity.audience() == null) {
            log.warn("[social] APPLE token not kept (configuration)");
            return;
        }
        try {
            Optional<AppleTokenClient.AppleGrant> grant =
                    appleTokenClient.exchange(identity.audience(), authorizationCode.strip());
            if (grant.isEmpty()) {
                log.warn("[social] APPLE token not kept (code refused)");
                return;
            }
            if (!identity.subject().equals(grant.get().subject())) {
                log.warn("[social] APPLE token not kept (code of another Apple account)");
                return;
            }
            Optional<UUID> rowId = authenticators.findId(SocialProvider.APPLE, identity.subject());
            if (rowId.isPresent()) {
                byte[] blob = cipher.encrypt(grant.get().refreshToken(), rowId.get().toString());
                if (authenticators.storeRevocationToken(rowId.get(), identity.audience(), blob)) {
                    return;
                }
            }
            // The link was removed (unlink, account deletion) while Apple issued this token: revoke it too.
            UUID context = UUID.randomUUID();
            revocationQueue.enqueue(context, identity.audience(),
                    cipher.encrypt(grant.get().refreshToken(), context.toString()));
            log.info("[social] APPLE token of a removed link queued for revocation");
        } catch (RuntimeException e) {
            log.warn("[social] APPLE token not kept ({})", e.getClass().getSimpleName());
        }
    }
}
