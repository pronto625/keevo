package com.keevo.identity.auth.application.service;

import com.keevo.identity.auth.domain.model.AuthTokens;
import com.keevo.identity.auth.domain.model.Tenant;
import com.keevo.identity.auth.domain.model.UserAuthenticatedEvent;
import com.keevo.identity.auth.domain.model.UserTenantMembership;
import com.keevo.identity.auth.domain.port.in.SelectTenantCommand;
import com.keevo.identity.auth.domain.port.in.SelectTenantUseCase;
import com.keevo.identity.auth.domain.port.out.TenantRepository;
import com.keevo.identity.auth.domain.port.out.UserMembershipRepository;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import com.keevo.shared.infrastructure.security.JwtTokenProvider;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
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
    private final JwtTokenProvider        jwtTokenProvider;
    private final SessionTokenIssuer      sessionTokenIssuer;
    private final ApplicationEventPublisher eventPublisher;

    public SelectTenantService(TenantRepository tenantRepository,
                                UserMembershipRepository membershipRepository,
                                JwtTokenProvider jwtTokenProvider,
                                SessionTokenIssuer sessionTokenIssuer,
                                ApplicationEventPublisher eventPublisher) {
        this.tenantRepository     = tenantRepository;
        this.membershipRepository = membershipRepository;
        this.jwtTokenProvider     = jwtTokenProvider;
        this.sessionTokenIssuer   = sessionTokenIssuer;
        this.eventPublisher       = eventPublisher;
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

        // Step 4 — Issue the session (claims built from current tenant/membership/employee
        // data — the same path refresh uses, so a renewed token never differs from a login).
        String schemaName = tenant.getSchemaName();
        String role       = membership.getRole();
        AuthTokens tokens = sessionTokenIssuer.issue(userId, tenant, role).tokens();

        // Step 7 — Publish audit event (now we know the tenant context)
        eventPublisher.publishEvent(new UserAuthenticatedEvent(
                userId, schemaName, role, null, Instant.now()));

        return tokens;
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
}
