package com.keevo.identity.auth.application.service;

import com.keevo.identity.api.Role;
import com.keevo.identity.auth.domain.model.AuthTokens;
import com.keevo.identity.auth.domain.model.DeviceInfo;
import com.keevo.identity.auth.domain.model.RefreshToken;
import com.keevo.identity.auth.domain.model.Tenant;
import com.keevo.identity.auth.domain.port.out.RefreshTokenRepository;
import com.keevo.shared.infrastructure.security.JwtProperties;
import com.keevo.shared.infrastructure.security.JwtTokenProvider;
import com.keevo.shared.infrastructure.persistence.TenantSchema;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;

/**
 * Issues a session (access + refresh token) for a user in a tenant.
 *
 * <p>Single place where access-token claims are built, shared by login (tenant
 * selection), registration and refresh. Refresh used to build its own reduced
 * token (tenantStatus forced to ACTIVE, no storeId), which logged employees out
 * and lifted suspensions on every renewal.
 */
@Component
public class SessionTokenIssuer {

    /** Session plus the id of the refresh token row, needed to chain rotations. */
    public record IssuedSession(AuthTokens tokens, UUID refreshTokenId) {}

    private final JwtTokenProvider jwtTokenProvider;
    private final JwtProperties jwtProperties;
    private final RefreshTokenRepository refreshTokenRepository;
    private final JdbcTemplate jdbcTemplate;

    public SessionTokenIssuer(JwtTokenProvider jwtTokenProvider,
                              JwtProperties jwtProperties,
                              RefreshTokenRepository refreshTokenRepository,
                              JdbcTemplate jdbcTemplate) {
        this.jwtTokenProvider = jwtTokenProvider;
        this.jwtProperties = jwtProperties;
        this.refreshTokenRepository = refreshTokenRepository;
        this.jdbcTemplate = jdbcTemplate;
    }

    /** New device session without device data (registration, tests). */
    public IssuedSession issue(UUID userId, Tenant tenant, String role) {
        return issue(userId, tenant, role, null, DeviceInfo.NONE);
    }

    /**
     * Story 16.11 — issues an access token (identity only: sub, tenantId, sid, iat, exp; plus the
     * indicative legacy claims while {@code keevo.jwt.legacy-claims} is on) and a refresh token in
     * device session {@code sessionId} (a new session when null). The refresh token lives
     * {@code refresh-token-expiry-days} from now (365, sliding: every renewal issues a new one), or
     * {@code super-admin-refresh-expiry-hours} (12) for the super-admin. Story 19.5 — the legacy {@code role} claim
     * is in the V1 vocabulary (MANAGER and VIEWER show as EMPLOYEE); V2 reads the real role from the profile.
     */
    public IssuedSession issue(UUID userId, Tenant tenant, String role, UUID sessionId, DeviceInfo device) {
        // Story 11.4 AC7: throws before the schema reaches any SQL (employee lookup, onboarding probe).
        // The SUPER_ADMIN system tenant (schema "public") is the only exception: no tenant SQL runs for it.
        boolean platformAdmin = TenantSchema.isPlatformAdminSession(tenant.getSchemaName(), role);
        String schema = platformAdmin ? tenant.getSchemaName() : TenantSchema.validate(tenant.getSchemaName());
        DeviceInfo dev = device != null ? device : DeviceInfo.NONE;

        UUID storeId = null;
        boolean passwordChangeRequired = false;
        if (Role.isStoreMember(role)) { // Story 19.5: EMPLOYEE, MANAGER, VIEWER
            try {
                Map<String, Object> row = jdbcTemplate.queryForMap(
                        "SELECT store_id, password_change_required FROM \""
                        + schema + "\".employees WHERE user_id = ? LIMIT 1", userId);
                storeId = (UUID) row.get("store_id");
                passwordChangeRequired = Boolean.TRUE.equals(row.get("password_change_required"));
            } catch (EmptyResultDataAccessException ignored) {
                // No employee row yet — JwtAuthFilter rejects the request as ACCOUNT_INACTIVE.
            }
        }

        UUID sid = sessionId != null ? sessionId : UUID.randomUUID();
        JwtTokenProvider.LegacyClaims legacy = jwtProperties.isLegacyClaims()
                ? new JwtTokenProvider.LegacyClaims(Role.v1Label(role), tenant.getStatus().name(), storeId,
                        passwordChangeRequired, platformAdmin || onboardingCompleted(schema))
                : null;
        String accessToken = jwtTokenProvider.generateAccessToken(userId, schema, sid, legacy);

        Instant now = Instant.now();
        String rawRefreshToken = jwtTokenProvider.generateRefreshToken();
        Instant expiresAt = platformAdmin
                ? now.plus(jwtProperties.getSuperAdminRefreshExpiryHours(), ChronoUnit.HOURS)
                : now.plus(jwtProperties.getRefreshTokenExpiryDays(), ChronoUnit.DAYS);
        RefreshToken saved = refreshTokenRepository.save(RefreshToken.create(
                userId, schema, hash(rawRefreshToken), expiresAt, sid, dev.deviceId(), dev.deviceLabel(), now));

        AuthTokens tokens = new AuthTokens(accessToken, rawRefreshToken,
                jwtProperties.getAccessTokenExpirySeconds(), userId, schema, role,
                storeId != null ? storeId.toString() : null, passwordChangeRequired);
        return new IssuedSession(tokens, saved.id());
    }

    /**
     * Onboarding is complete once tenant_preferences has a row (written by
     * OnboardingService). A tenant whose tables are not provisioned yet has not
     * completed it either.
     *
     * <p>Story 12.12: this runs inside the caller's transaction (login / select-tenant /
     * refresh). Catching a "relation does not exist" error there is not enough on Postgres —
     * the transaction is already aborted and the following refresh-token save fails. So the
     * table's existence is probed with {@code to_regclass} (never errors) and no SQL error is
     * swallowed here.
     */
    private boolean onboardingCompleted(String schema) {
        return isOnboardingCompleted(schema);
    }

    /** Story 16.11 — onboarding state for the profile (the app no longer reads it from the token). */
    public boolean isOnboardingCompleted(String schema) {
        TenantSchema.validate(schema); // re-checked at use
        Boolean tableExists = jdbcTemplate.queryForObject(
                "SELECT to_regclass(?) IS NOT NULL", Boolean.class,
                "\"" + schema + "\".tenant_preferences");
        if (!Boolean.TRUE.equals(tableExists)) {
            return false;
        }
        Boolean done = jdbcTemplate.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM \"" + schema + "\".tenant_preferences)", Boolean.class);
        return Boolean.TRUE.equals(done);
    }

    /** SHA-256 of the raw opaque token — deterministic, so it can be looked up. */
    public static String hash(String rawToken) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(rawToken.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 algorithm not available", e);
        }
    }
}
