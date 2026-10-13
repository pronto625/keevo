package com.keevo.identity.auth.application.service;

import com.keevo.identity.auth.domain.model.AuthTokens;
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

    public IssuedSession issue(UUID userId, Tenant tenant, String role) {
        // Story 11.4 AC7: throws before the schema reaches any SQL (employee lookup, onboarding probe)
        String schema = TenantSchema.validate(tenant.getSchemaName());

        UUID storeId = null;
        boolean passwordChangeRequired = false;
        String firstName = null;
        if ("EMPLOYEE".equals(role)) {
            try {
                Map<String, Object> row = jdbcTemplate.queryForMap(
                        "SELECT store_id, password_change_required, first_name FROM \""
                        + schema + "\".employees WHERE user_id = ? LIMIT 1", userId);
                storeId = (UUID) row.get("store_id");
                passwordChangeRequired = Boolean.TRUE.equals(row.get("password_change_required"));
                firstName = (String) row.get("first_name");
            } catch (EmptyResultDataAccessException ignored) {
                // No employee row yet — JwtAuthFilter rejects the request as ACCOUNT_INACTIVE.
            }
        }

        String accessToken = jwtTokenProvider.generateAccessToken(
                userId, schema, role, tenant.getStatus().name(),
                storeId, passwordChangeRequired, firstName, onboardingCompleted(schema));

        String rawRefreshToken = jwtTokenProvider.generateRefreshToken();
        Instant expiresAt = Instant.now().plus(jwtProperties.getRefreshTokenExpiryDays(), ChronoUnit.DAYS);
        RefreshToken saved = refreshTokenRepository.save(
                RefreshToken.create(userId, schema, hash(rawRefreshToken), expiresAt));

        long expiresIn = (long) jwtProperties.getAccessTokenExpiryHours() * 3600;
        AuthTokens tokens = new AuthTokens(accessToken, rawRefreshToken, expiresIn, userId, schema, role,
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
        TenantSchema.validate(schema); // issue() already validated it; re-checked at use
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
