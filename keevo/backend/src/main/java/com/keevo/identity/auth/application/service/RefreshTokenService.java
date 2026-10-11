package com.keevo.identity.auth.application.service;

import com.keevo.identity.auth.domain.model.AuthTokens;
import com.keevo.identity.auth.domain.model.RefreshToken;
import com.keevo.identity.auth.domain.model.Tenant;
import com.keevo.identity.auth.domain.model.TenantStatus;
import com.keevo.identity.auth.domain.model.UserTenantMembership;
import com.keevo.identity.auth.domain.port.in.RefreshTokenUseCase;
import com.keevo.identity.auth.domain.port.out.RefreshTokenRepository;
import com.keevo.identity.auth.domain.port.out.TenantRepository;
import com.keevo.identity.auth.domain.port.out.UserMembershipRepository;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;

/**
 * RefreshTokenService — renews a session from a refresh token.
 *
 * <p>This is how a user stays signed in: the app never asks for the password
 * again while it holds a valid refresh token. Each refresh token is single-use:
 * exchanging it marks it as replaced by the new one (rotation).
 *
 * <ul>
 *   <li>A replaced token presented again while its successor has never been used
 *       means the client lost the previous response (dropped mobile connection):
 *       a new session is issued and the unused successor is superseded, so only one
 *       token of the chain is ever alive. The window counts from the first exchange.</li>
 *   <li>A replaced token presented again after its successor was used, or a superseded
 *       token presented at all, means two parties hold the same session: every session
 *       of the user is revoked.</li>
 *   <li>A token revoked outright (logout, password change, deactivation), or whose
 *       successor was, is refused.</li>
 * </ul>
 *
 * <p>The renewed access token is built by {@link SessionTokenIssuer} from current
 * data (membership role, tenant status, employee store), exactly like a login.
 */
@Service
public class RefreshTokenService implements RefreshTokenUseCase {

    private static final Logger log = LoggerFactory.getLogger(RefreshTokenService.class);

    /** How long a client that lost a refresh response can still recover with the previous token. */
    static final Duration LOST_RESPONSE_WINDOW = Duration.ofDays(7);

    /** Upper bound when following a rotation chain (guards against a corrupt cycle). */
    private static final int MAX_CHAIN_HOPS = 20;

    private final RefreshTokenRepository refreshTokenRepository;
    private final TenantRepository tenantRepository;
    private final UserMembershipRepository membershipRepository;
    private final SessionTokenIssuer issuer;

    public RefreshTokenService(RefreshTokenRepository refreshTokenRepository,
                               TenantRepository tenantRepository,
                               UserMembershipRepository membershipRepository,
                               SessionTokenIssuer issuer) {
        this.refreshTokenRepository = refreshTokenRepository;
        this.tenantRepository = tenantRepository;
        this.membershipRepository = membershipRepository;
        this.issuer = issuer;
    }

    @Override
    @Transactional(noRollbackFor = DomainException.class)
    public AuthTokens refresh(String rawRefreshToken) {
        RefreshToken presented = refreshTokenRepository.findByHash(SessionTokenIssuer.hash(rawRefreshToken))
                .orElseThrow(RefreshTokenService::invalid);

        if (Instant.now().isAfter(presented.expiresAt())) {
            throw invalid();
        }
        if (presented.revoked()) {
            recoverLostResponseOrReject(presented);
        }

        Tenant tenant = tenantRepository.findBySchemaName(presented.tenantId())
                .filter(t -> t.getStatus() != TenantStatus.DELETED)
                .orElseThrow(RefreshTokenService::invalid);
        UserTenantMembership membership = membershipRepository
                .findByUserIdAndTenantId(presented.userId(), tenant.getId())
                .filter(UserTenantMembership::isActive)
                .orElseThrow(RefreshTokenService::invalid);

        var session = issuer.issue(presented.userId(), tenant, membership.getRole());
        refreshTokenRepository.save(presented.isRotated()
                ? presented.withSuccessor(session.refreshTokenId())
                : presented.rotatedTo(session.refreshTokenId(), Instant.now()));
        return session.tokens();
    }

    /**
     * Revokes the session the token belongs to: the token itself and, when it was
     * already exchanged (the app signs out holding a token whose renewal answer it
     * never received), the live successor too.
     */
    @Override
    @Transactional
    public void revoke(String rawRefreshToken) {
        RefreshToken token = refreshTokenRepository.findByHash(SessionTokenIssuer.hash(rawRefreshToken))
                .orElse(null);
        for (int hops = 0; token != null && hops < MAX_CHAIN_HOPS; hops++) {
            if (!token.revoked()) {
                refreshTokenRepository.save(token.revokedOutright());
                return;
            }
            if (!token.isRotated()) {
                return;
            }
            token = refreshTokenRepository.findById(token.replacedById()).orElse(null);
        }
    }

    /**
     * Returns normally only when [presented] was rotated recently and its
     * successor never reached the client, after superseding that successor;
     * throws otherwise. When two parties hold the session, revokes every
     * session of the user (noRollbackFor keeps that write).
     */
    private void recoverLostResponseOrReject(RefreshToken presented) {
        if (presented.isSuperseded()) {
            throw replayed(presented);
        }
        if (!presented.isRotated()) {
            throw invalid();
        }
        RefreshToken successor = refreshTokenRepository.findById(presented.replacedById()).orElse(null);
        if (successor == null) {
            throw invalid();
        }
        if (successor.isRotated() || successor.isSuperseded()) {
            throw replayed(presented);
        }
        if (successor.revoked()) {
            // Revoked outright: signed out, password changed, or deactivated.
            throw invalid();
        }
        boolean withinWindow = presented.rotatedAt() != null
                && presented.rotatedAt().plus(LOST_RESPONSE_WINDOW).isAfter(Instant.now());
        if (!withinWindow) {
            throw invalid();
        }
        refreshTokenRepository.save(successor.supersededAt(Instant.now()));
    }

    private DomainException replayed(RefreshToken presented) {
        log.warn("[Auth] Refresh token replayed after rotation — revoking all sessions of user {}",
                presented.userId());
        refreshTokenRepository.revokeAllByUserId(presented.userId());
        return invalid();
    }

    private static DomainException invalid() {
        return new DomainException(ErrorCode.REFRESH_TOKEN_INVALID);
    }
}
