package com.keevo.identity.auth.application.service;

import com.keevo.identity.auth.domain.model.AuthTokens;
import com.keevo.identity.auth.domain.model.RefreshToken;
import com.keevo.identity.auth.domain.model.Tenant;
import com.keevo.identity.auth.domain.model.UserAuthenticatedEvent;
import com.keevo.identity.auth.domain.model.UserTenantMembership;
import com.keevo.identity.auth.domain.port.in.SelectTenantCommand;
import com.keevo.identity.auth.domain.port.in.SelectTenantUseCase;
import com.keevo.identity.auth.domain.port.out.RefreshTokenRepository;
import com.keevo.identity.auth.domain.port.out.TenantRepository;
import com.keevo.identity.auth.domain.port.out.UserMembershipRepository;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import com.keevo.shared.infrastructure.security.JwtProperties;
import com.keevo.shared.infrastructure.security.JwtTokenProvider;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/**
 * SelectTenantService — Orchestrates login step 2 (Story 1.7 two-step login).
 *
 * <p>GoF Pattern: Façade — coordinates:
 * loginToken validation → membership verification → access/refresh token generation →
 * refresh token persistence → audit event.
 *
 * <p>Architecture rules:
 * - NEVER called without a valid loginToken from AuthenticationService
 * - Injects all ports via constructor (hexagonal rule)
 * - Entire flow is @Transactional (membership check + refresh token persist)
 */
@Service
public class SelectTenantService implements SelectTenantUseCase {

    private final TenantRepository        tenantRepository;
    private final UserMembershipRepository membershipRepository;
    private final RefreshTokenRepository  refreshTokenRepository;
    private final JwtTokenProvider        jwtTokenProvider;
    private final JwtProperties           jwtProperties;
    private final ApplicationEventPublisher eventPublisher;

    public SelectTenantService(TenantRepository tenantRepository,
                                UserMembershipRepository membershipRepository,
                                RefreshTokenRepository refreshTokenRepository,
                                JwtTokenProvider jwtTokenProvider,
                                JwtProperties jwtProperties,
                                ApplicationEventPublisher eventPublisher) {
        this.tenantRepository    = tenantRepository;
        this.membershipRepository = membershipRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.jwtTokenProvider    = jwtTokenProvider;
        this.jwtProperties       = jwtProperties;
        this.eventPublisher      = eventPublisher;
    }

    @Override
    @Transactional
    public AuthTokens select(SelectTenantCommand command) {
        // Step 1 — Parse and validate loginToken
        UUID userId = parseAndValidateLoginToken(command.loginToken());

        // Step 2 — Resolve tenantCode → Tenant
        Tenant tenant = tenantRepository.findByCode(command.tenantCode())
                .orElseThrow(() -> new DomainException(ErrorCode.TENANT_NOT_FOUND));

        // Step 3 — Verify user has an active membership in this tenant
        UserTenantMembership membership = membershipRepository
                .findByUserIdAndTenantId(userId, tenant.getId())
                .filter(UserTenantMembership::isActive)
                .orElseThrow(() -> new DomainException(ErrorCode.MEMBERSHIP_NOT_FOUND));

        // Step 4 — Extract role and schemaName from membership + tenant
        String schemaName = tenant.getSchemaName();
        String role       = membership.getRole();

        // Step 5 — Generate full RS256 access token (24h, scope=access / no scope claim)
        String accessToken = jwtTokenProvider.generateAccessToken(
                userId, schemaName, role, tenant.getStatus().name());

        // Step 6 — Generate opaque refresh token and persist its SHA-256 hash
        String rawRefreshToken = jwtTokenProvider.generateRefreshToken();
        String tokenHash = hashToken(rawRefreshToken);
        Instant expiresAt = Instant.now().plus(jwtProperties.getRefreshTokenExpiryDays(), ChronoUnit.DAYS);
        RefreshToken refreshToken = RefreshToken.create(userId, schemaName, tokenHash, expiresAt);
        refreshTokenRepository.save(refreshToken);

        // Step 7 — Publish audit event (now we know the tenant context)
        eventPublisher.publishEvent(new UserAuthenticatedEvent(
                userId, schemaName, role, null, Instant.now()));

        long expiresIn = (long) jwtProperties.getAccessTokenExpiryHours() * 3600;
        return new AuthTokens(accessToken, rawRefreshToken, expiresIn, userId, schemaName, role);
    }

    // ── Private helpers ─────────────────────────────────────────────────────

    /**
     * Parses the loginToken, verifies it has scope=login_pending, and extracts the userId.
     *
     * @throws DomainException TOKEN_EXPIRED if the token is expired
     * @throws DomainException TOKEN_INVALID if scope != login_pending or token is malformed
     */
    private UUID parseAndValidateLoginToken(String loginToken) {
        try {
            Claims claims = jwtTokenProvider.parseToken(loginToken);
            String scope = jwtTokenProvider.extractScope(claims);
            if (!"login_pending".equals(scope)) {
                // access token used as loginToken — reject
                throw new DomainException(ErrorCode.TOKEN_INVALID);
            }
            return jwtTokenProvider.extractUserId(claims);
        } catch (ExpiredJwtException e) {
            throw new DomainException(ErrorCode.TOKEN_EXPIRED);
        } catch (JwtException e) {
            throw new DomainException(ErrorCode.TOKEN_INVALID);
        }
    }

    /** SHA-256 hash of raw token — deterministic for DB lookup. */
    private String hashToken(String rawToken) {
        try {
            var md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(rawToken.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 algorithm not available", e);
        }
    }
}
