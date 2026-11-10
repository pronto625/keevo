package com.keevo.identity.auth.application.service;

import com.keevo.identity.api.Role;
import com.keevo.identity.auth.domain.model.SocialIdentity;
import com.keevo.identity.auth.domain.model.SocialProvider;
import com.keevo.identity.auth.domain.model.User;
import com.keevo.identity.auth.domain.port.in.SocialAccountUseCase;
import com.keevo.identity.auth.domain.port.out.UserAuthenticatorRepository;
import com.keevo.identity.auth.domain.port.out.UserAuthenticatorRepository.LinkedIdentity;
import com.keevo.identity.auth.domain.port.out.UserRepository;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Story 19.3b — Façade of the account settings « Comptes Google et Apple »: the way out of a 19.3 sign-in refused
 * with {@code EMAIL_ALREADY_EXISTS} (sign in by code or password, then link).
 *
 * <ul>
 *   <li>link: identity proven outside any transaction ({@link SocialIdentityVerifier}: limit, Strategy, nonce), then
 *       one transaction holding the user's row lock (two links of one user run one after the other): already linked
 *       to this user → no-op; to another → {@code SOCIAL_ACCOUNT_LINKED_ELSEWHERE}; another identity of the provider
 *       → {@code SOCIAL_PROVIDER_ALREADY_LINKED}; else inserted ({@code ON CONFLICT DO NOTHING}, re-read on a
 *       concurrent link). Never changes the account's email, names or roles. The Apple code is exchanged after
 *       commit ({@link AppleTokenStore}).</li>
 *   <li>unlink: same lock; refused with {@code LAST_SIGN_IN_METHOD} when the account would keep no password, phone,
 *       usable email (an Apple relay address stops working with Apple) nor identity of another provider; the stored Apple tokens go to the revocation outbox in the same
 *       transaction.</li>
 * </ul>
 * The super-admin never links ({@code SOCIAL_TOKEN_INVALID}, as at sign-in).
 *
 * <p>Cost: list 1 query; link 1 lock + 1 user read + 1 subject lookup + 1 per-user read + ≤ 1 insert; unlink 1 lock +
 * 1 user read + 1 per-user read + 1 {@code INSERT … SELECT} + 1 DELETE (all indexed).
 */
@Service
public class SocialAccountService implements SocialAccountUseCase {

    private static final Logger log = LoggerFactory.getLogger(SocialAccountService.class);

    private final SocialIdentityVerifier identityVerifier;
    private final UserRepository userRepository;
    private final UserAuthenticatorRepository authenticators;
    private final AppleTokenStore appleTokenStore;
    private final TransactionTemplate transactionTemplate;

    public SocialAccountService(SocialIdentityVerifier identityVerifier, UserRepository userRepository,
                                UserAuthenticatorRepository authenticators, AppleTokenStore appleTokenStore,
                                TransactionTemplate transactionTemplate) {
        this.identityVerifier = identityVerifier;
        this.userRepository = userRepository;
        this.authenticators = authenticators;
        this.appleTokenStore = appleTokenStore;
        this.transactionTemplate = transactionTemplate;
    }

    @Override
    public List<LinkedSocialAccount> list(UUID userId) {
        return toAccounts(authenticators.findByUser(userId));
    }

    @Override
    public List<LinkedSocialAccount> link(LinkCommand command) {
        SocialIdentity identity = identityVerifier.verify(command.provider(), command.token(), command.nonce(),
                command.clientIp());
        List<LinkedSocialAccount> result = transactionTemplate.execute(status -> {
            User user = lockedUser(command.userId());
            if (user.getRole() == Role.SUPER_ADMIN) {
                throw new DomainException(ErrorCode.SOCIAL_TOKEN_INVALID);
            }
            Optional<UUID> holder = authenticators.findUserId(identity.provider(), identity.subject());
            if (holder.isPresent()) {
                requireHeldBy(holder.get(), user.getId());
                return list(user.getId());
            }
            List<LinkedIdentity> linked = authenticators.findByUser(user.getId());
            if (linked.stream().anyMatch(l -> l.provider() == identity.provider())) {
                throw new DomainException(ErrorCode.SOCIAL_PROVIDER_ALREADY_LINKED);
            }
            if (!authenticators.insertIfAbsent(user.getId(), identity.provider(), identity.subject())) {
                // Linked meanwhile by a concurrent sign-in or link: the committed holder decides.
                requireHeldBy(authenticators.findUserId(identity.provider(), identity.subject())
                        .orElseThrow(() -> new DomainException(ErrorCode.SOCIAL_ACCOUNT_LINKED_ELSEWHERE)), user.getId());
            } else {
                log.info("[social] {} identity linked from settings to account {}", identity.provider(), user.getId());
            }
            return list(user.getId());
        });
        if (command.authorizationCode() != null) {
            appleTokenStore.storeIfPossible(identity, command.authorizationCode());
        }
        return result;
    }

    @Override
    public void unlink(UUID userId, SocialProvider provider) {
        transactionTemplate.executeWithoutResult(status -> {
            User user = lockedUser(userId);
            List<LinkedIdentity> linked = authenticators.findByUser(userId);
            if (linked.stream().noneMatch(l -> l.provider() == provider)) {
                return;
            }
            boolean otherWayIn = user.getPasswordHash() != null || user.getPhoneNumber() != null
                    || emailStaysUsable(user.getEmail(), provider)
                    || linked.stream().anyMatch(l -> l.provider() != provider);
            if (!otherWayIn) {
                throw new DomainException(ErrorCode.LAST_SIGN_IN_METHOD);
            }
            int removed = authenticators.unlink(userId, provider);
            log.info("[social] {} identity unlinked from account {} ({} row(s))", provider, userId, removed);
        });
    }

    /** Domain of Apple's « Hide my email » relay addresses. */
    static final String APPLE_RELAY_DOMAIN = "@privaterelay.appleid.com";

    /**
     * An email is a way in (sign-in code), except Apple's private relay address once Apple is unlinked: Apple stops
     * forwarding to it when the link is revoked.
     */
    static boolean emailStaysUsable(String email, SocialProvider unlinked) {
        if (email == null) {
            return false;
        }
        return unlinked != SocialProvider.APPLE
                || !email.toLowerCase(java.util.Locale.ROOT).endsWith(APPLE_RELAY_DOMAIN);
    }

    private User lockedUser(UUID userId) {
        if (!userRepository.lockById(userId)) {
            throw new DomainException(ErrorCode.USER_NOT_FOUND);
        }
        return userRepository.findById(userId).orElseThrow(() -> new DomainException(ErrorCode.USER_NOT_FOUND));
    }

    private static void requireHeldBy(UUID holder, UUID userId) {
        if (!holder.equals(userId)) {
            throw new DomainException(ErrorCode.SOCIAL_ACCOUNT_LINKED_ELSEWHERE);
        }
    }

    private static List<LinkedSocialAccount> toAccounts(List<LinkedIdentity> linked) {
        return linked.stream().map(l -> new LinkedSocialAccount(l.provider(), l.linkedAt())).toList();
    }
}
