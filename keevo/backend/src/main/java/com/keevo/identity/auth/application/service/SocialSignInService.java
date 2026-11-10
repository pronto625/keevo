package com.keevo.identity.auth.application.service;

import com.keevo.identity.api.Role;
import com.keevo.identity.auth.domain.model.SocialIdentity;
import com.keevo.identity.auth.domain.model.SocialProvider;
import com.keevo.identity.auth.domain.model.User;
import com.keevo.identity.auth.domain.model.UserMembershipInfo;
import com.keevo.identity.auth.domain.port.in.LoginSessionResult;
import com.keevo.identity.auth.domain.port.in.SocialSignInCommand;
import com.keevo.identity.auth.domain.port.in.SocialSignInUseCase;
import com.keevo.identity.auth.domain.port.out.UserAuthenticatorRepository;
import com.keevo.identity.auth.domain.port.out.UserMembershipRepository;
import com.keevo.identity.auth.domain.port.out.UserRepository;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import com.keevo.shared.infrastructure.security.JwtTokenProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Story 19.3 — Google / Apple sign-in (port of Bref's {@code findOrCreateSocialUser}), ending in login step 1 exactly
 * like {@code /auth/login}.
 *
 * <p>Façade: token proven by {@link SocialIdentityVerifier} (per-IP limit on refused tokens, provider Strategy, nonce —
 * outside any transaction: it may fetch the JWKS) → account resolution in one transaction:
 * <ol>
 *   <li>known {@code (provider, subject)} → that account; a verified email is added when the account has none and no
 *       other account holds it;</li>
 *   <li>else a verified email of an existing account → linked only if that account is an owner account (Story 19.6: an
 *       active OWNER membership, or no membership at all) whose email is verified, otherwise {@code EMAIL_ALREADY_EXISTS} (members' and unproven emails are never linked);</li>
 *   <li>else a new OWNER account without company (verified email, or a technical username when the token has none).</li>
 * </ol>
 * The super-admin never signs in this way ({@code SOCIAL_TOKEN_INVALID}). Concurrent first logins: the user insert and
 * the authenticator insert are both {@code ON CONFLICT DO NOTHING}, then re-read.
 *
 * <p>Complexity: 0 network calls with cached keys, 1 lookup on the {@code (provider, subject)} unique index, at most
 * 1 {@code lower(email)} lookup, at most 1 user insert + 1 authenticator insert (+ 1 re-read on conflict), 1
 * memberships JOIN.
 */
@Service
public class SocialSignInService implements SocialSignInUseCase {

    private static final Logger log = LoggerFactory.getLogger(SocialSignInService.class);

    /** Hex lengths of the technical username suffix, tried in order on a (rare) collision. */
    static final int[] USERNAME_HEX_LENGTHS = {12, 20, 28, 36};
    static final int NAME_MAX = 100;

    private final SocialIdentityVerifier identityVerifier;
    private final UserRepository userRepository;
    private final UserAuthenticatorRepository authenticatorRepository;
    private final UserMembershipRepository membershipRepository;
    private final JwtTokenProvider jwtTokenProvider;
    private final TransactionTemplate transactionTemplate;
    /** Story 19.3b — Apple refresh token kept for revocation (after commit, asynchronous, best effort). */
    private final AppleTokenStore appleTokenStore;
    private final Clock clock;

    public SocialSignInService(SocialIdentityVerifier identityVerifier,
                               UserRepository userRepository,
                               UserAuthenticatorRepository authenticatorRepository,
                               UserMembershipRepository membershipRepository,
                               JwtTokenProvider jwtTokenProvider,
                               TransactionTemplate transactionTemplate,
                               AppleTokenStore appleTokenStore,
                               Clock clock) {
        this.identityVerifier = identityVerifier;
        this.appleTokenStore = appleTokenStore;
        this.userRepository = userRepository;
        this.authenticatorRepository = authenticatorRepository;
        this.membershipRepository = membershipRepository;
        this.jwtTokenProvider = jwtTokenProvider;
        this.transactionTemplate = transactionTemplate;
        this.clock = clock;
    }

    @Override
    public LoginSessionResult signIn(SocialSignInCommand command) {
        // Outside any transaction: verification may fetch the provider's keys over HTTP. Only refusals are counted.
        SocialIdentity identity = identityVerifier.verify(command.provider(), command.token(), command.nonce(),
                command.clientIp());
        String firstName = firstNonBlank(command.firstName(), identity.firstName());
        String lastName = firstNonBlank(command.lastName(), identity.lastName());
        LoginSessionResult result = transactionTemplate.execute(status -> {
            User user = resolve(identity, firstName, lastName, clock.instant());
            List<UserMembershipInfo> memberships = userRepository.findMembershipsWithTenantInfo(user.getId());
            return new LoginSessionResult(jwtTokenProvider.generateLoginToken(user.getId()), memberships);
        });
        // Story 19.3b — committed: the authenticator row exists; the Apple code exchange runs asynchronously.
        appleTokenStore.storeIfPossible(identity, command.authorizationCode());
        return result;
    }

    private User resolve(SocialIdentity identity, String firstName, String lastName, Instant now) {
        Optional<User> known = findBySubject(identity);
        if (known.isPresent()) {
            return addEmailIfMissing(known.get(), identity, now);
        }
        String email = identity.verifiedEmail();
        if (email != null) {
            Optional<User> byEmail = userRepository.findByEmail(email);
            if (byEmail.isPresent()) {
                return link(byEmail.get(), identity);
            }
        }
        return create(identity, email, firstName, lastName, now);
    }

    private Optional<User> findBySubject(SocialIdentity identity) {
        return authenticatorRepository.findUserId(identity.provider(), identity.subject())
                .map(id -> userRepository.findById(id)
                        .orElseThrow(() -> new DomainException(ErrorCode.SOCIAL_TOKEN_INVALID)))
                .map(SocialSignInService::refuseSuperAdmin);
    }

    /**
     * Step 1 — a known account without email receives the token's verified email when no other account holds it (one
     * conditional UPDATE: no read-then-write race).
     */
    private User addEmailIfMissing(User user, SocialIdentity identity, Instant now) {
        String email = identity.verifiedEmail();
        if (email == null || user.getEmail() != null || userRepository.setEmailIfAbsent(user.getId(), email, now) == 0) {
            return user;
        }
        log.info("[social] {} email added to account {}", identity.provider(), user.getId());
        return user.withEmail(email).withEmailVerifiedAt(now);
    }

    /**
     * Step 2 — links the identity to the account holding its verified email, only for an owner account whose email is
     * verified (Bref, FUS-FR6): a member's email is typed by the owner and an unverified one was never proven.
     *
     * <p>Story 19.6 — « owner account » is judged on memberships: at least one active OWNER membership, or no
     * membership at all (an account created by a sign-in code, without company yet). Two {@code EXISTS}, at most.
     */
    private User link(User user, SocialIdentity identity) {
        refuseSuperAdmin(user);
        boolean ownerAccount = membershipRepository.hasActiveOwnerMembership(user.getId())
                || !membershipRepository.hasAnyMembership(user.getId());
        if (!ownerAccount || user.getEmailVerifiedAt() == null) {
            throw new DomainException(ErrorCode.EMAIL_ALREADY_EXISTS);
        }
        if (!authenticatorRepository.insertIfAbsent(user.getId(), identity.provider(), identity.subject())) {
            // A concurrent first login linked this subject meanwhile: the account holding it wins.
            return findBySubject(identity).orElseThrow(() -> new DomainException(ErrorCode.SOCIAL_TOKEN_INVALID));
        }
        log.info("[social] {} identity linked to account {}", identity.provider(), user.getId());
        return user;
    }

    /**
     * Step 3 — new OWNER account without company. Without a verified email it is identified by a technical
     * username derived from the subject (the {@code users_identifier_present} CHECK needs one identifier).
     *
     * <p>A user insert that conflicts means a concurrent first login (same email, or same subject → same username)
     * committed first: resolution is re-run once on the committed rows. A username held by an unrelated account
     * (hash prefix collision) takes a longer suffix.
     */
    private User create(SocialIdentity identity, String email, String firstName, String lastName, Instant now) {
        for (int hexLength : email != null ? new int[]{0} : USERNAME_HEX_LENGTHS) {
            String username = email != null ? null : technicalUsername(identity, hexLength);
            User account = User.newSocialAccount(email, username, firstName, lastName, now);
            if (userRepository.insertIfAbsent(account)) {
                if (!authenticatorRepository.insertIfAbsent(account.getId(), identity.provider(), identity.subject())) {
                    // The subject was linked to another account meanwhile: roll back this account.
                    throw new DomainException(ErrorCode.USER_ALREADY_EXISTS);
                }
                log.info("[social] {} account created {}", identity.provider(), account.getId());
                return account;
            }
            Optional<User> known = findBySubject(identity);
            if (known.isPresent()) {
                return known.get();
            }
            if (email != null) {
                return link(userRepository.findByEmail(email)
                        .orElseThrow(() -> new DomainException(ErrorCode.USER_ALREADY_EXISTS)), identity);
            }
        }
        throw new DomainException(ErrorCode.USER_ALREADY_EXISTS);
    }

    /** Bref-format username: {@code <provider>-} + the first hex chars of SHA-256(subject); never shown to the user. */
    public static String technicalUsername(SocialIdentity identity, int hexLength) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(identity.subject().getBytes(StandardCharsets.UTF_8));
            return identity.provider().name().toLowerCase(Locale.ROOT) + "-"
                    + HexFormat.of().formatHex(digest).substring(0, hexLength);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /** Answered like an invalid token: the super-admin keeps its password login only. */
    private static User refuseSuperAdmin(User user) {
        if (user.getRole() == Role.SUPER_ADMIN) {
            throw new DomainException(ErrorCode.SOCIAL_TOKEN_INVALID);
        }
        return user;
    }

    private static String firstNonBlank(String preferred, String fallback) {
        String value = User.normalizeIdentifier(preferred);
        if (value == null) {
            value = User.normalizeIdentifier(fallback);
        }
        if (value == null || value.length() <= NAME_MAX) {
            return value;
        }
        // Never cut a surrogate pair in half.
        int end = Character.isHighSurrogate(value.charAt(NAME_MAX - 1)) ? NAME_MAX - 1 : NAME_MAX;
        return value.substring(0, end);
    }
}
