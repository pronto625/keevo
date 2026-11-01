package com.keevo.identity.auth.application.service;

import com.keevo.identity.api.PlanType;
import com.keevo.identity.auth.domain.model.RefreshToken;
import com.keevo.identity.auth.domain.model.Tenant;
import com.keevo.identity.api.TenantStatus;
import com.keevo.identity.auth.domain.port.out.RefreshTokenRepository;
import com.keevo.shared.infrastructure.security.JwtProperties;
import com.keevo.shared.infrastructure.security.JwtTokenProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.BadSqlGrammarException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.SQLException;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SessionTokenIssuerTest {

    @Mock RefreshTokenRepository refreshTokenRepository;
    @Mock JdbcTemplate jdbcTemplate;

    private JwtTokenProvider jwt;
    private SessionTokenIssuer issuer;

    private static final UUID USER_ID = UUID.randomUUID();
    private static final String SCHEMA = "kv_abc123";

    @BeforeEach
    void setUp() {
        JwtProperties props = new JwtProperties(30, 365);
        jwt = TestJwt.provider(props);
        issuer = new SessionTokenIssuer(jwt, props, refreshTokenRepository, jdbcTemplate);
        lenient().when(refreshTokenRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    private static Tenant tenant(TenantStatus status) {
        return new Tenant(UUID.randomUUID(), "KV-ABC123", SCHEMA, status, PlanType.FREE, Instant.now());
    }

    private void onboardingDone(boolean done) {
        tablePresent(true);
        when(jdbcTemplate.queryForObject(contains("tenant_preferences"), eq(Boolean.class))).thenReturn(done);
    }

    /** Story 12.12 — to_regclass probe for "schema".tenant_preferences. */
    private void tablePresent(boolean present) {
        when(jdbcTemplate.queryForObject(contains("to_regclass"), eq(Boolean.class),
                eq("\"" + SCHEMA + "\".tenant_preferences"))).thenReturn(present);
    }

    @Test
    void invalidSchema_throwsBeforeAnyJdbcOrRefreshToken() {
        // Story 11.4 AC7: request path — the schema never reaches SQL, nothing is persisted.
        Tenant bad = new Tenant(UUID.randomUUID(), "KV-ABC123", "public", TenantStatus.ACTIVE,
                PlanType.FREE, Instant.now());

        assertThatThrownBy(() -> issuer.issue(USER_ID, bad, "EMPLOYEE"))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(jdbcTemplate);
        verify(refreshTokenRepository, never()).save(any());
    }

    @Test
    void superAdminOnSystemTenant_issuesSessionWithoutTenantSql() {
        // W1: the KV-ADMIN system tenant lives in "public" — the dashboard login used to fail with a 500.
        Tenant admin = new Tenant(UUID.randomUUID(), "KV-ADMIN", "public", TenantStatus.ACTIVE,
                PlanType.FREE, Instant.now());

        var session = issuer.issue(USER_ID, admin, "SUPER_ADMIN");

        var claims = jwt.parseToken(session.tokens().accessToken());
        assertThat(claims.get("role")).isEqualTo("SUPER_ADMIN"); // indicative legacy claim
        assertThat(jwt.extractTenantId(claims)).isEqualTo("public");
        // Story 16.11 — super-admin sessions slide over 12 h, not 365 days.
        var saved = ArgumentCaptor.forClass(RefreshToken.class);
        verify(refreshTokenRepository).save(saved.capture());
        assertThat(saved.getValue().expiresAt())
                .isBetween(Instant.now().plus(11, java.time.temporal.ChronoUnit.HOURS),
                        Instant.now().plus(12, java.time.temporal.ChronoUnit.HOURS).plusSeconds(5));
        assertThat(claims.get("onboardingCompleted")).isEqualTo(true);
        verifyNoInteractions(jdbcTemplate);
    }

    @Test
    void publicSchema_withTenantRole_stillThrows() {
        Tenant bad = new Tenant(UUID.randomUUID(), "KV-ADMIN", "public", TenantStatus.ACTIVE,
                PlanType.FREE, Instant.now());

        assertThatThrownBy(() -> issuer.issue(USER_ID, bad, "OWNER"))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(jdbcTemplate);
        verify(refreshTokenRepository, never()).save(any());
    }

    @Test
    void employeeToken_carriesStoreAndRealTenantStatus() {
        UUID store = UUID.randomUUID();
        onboardingDone(true);
        when(jdbcTemplate.queryForMap(contains("employees"), eq(USER_ID))).thenReturn(Map.of(
                "store_id", store, "password_change_required", true, "first_name", "Loïc"));

        var session = issuer.issue(USER_ID, tenant(TenantStatus.SUSPENDED), "EMPLOYEE");

        // Story 16.11 — legacy claims stay indicative for V1 apps; never a first name.
        var claims = jwt.parseToken(session.tokens().accessToken());
        assertThat(claims.get("storeId")).isEqualTo(store.toString());
        assertThat(claims.get("tenantStatus")).isEqualTo("SUSPENDED");
        assertThat(claims.get("passwordChangeRequired")).isEqualTo(true);
        assertThat(claims.get("role")).isEqualTo("EMPLOYEE");
        assertThat(claims).doesNotContainKey("firstName");
        assertThat(jwt.extractSessionId(claims)).isNotNull();
        assertThat(session.tokens().storeId()).isEqualTo(store.toString());
    }

    @Test
    void legacyClaimsOff_tokenCarriesIdentityOnly() {
        JwtProperties props = new JwtProperties(30, 365);
        props.setLegacyClaims(false);
        JwtTokenProvider strict = TestJwt.provider(props);
        SessionTokenIssuer strictIssuer = new SessionTokenIssuer(strict, props, refreshTokenRepository, jdbcTemplate);
        when(jdbcTemplate.queryForMap(contains("employees"), eq(USER_ID))).thenReturn(Map.of(
                "store_id", UUID.randomUUID(), "password_change_required", true));

        var session = strictIssuer.issue(USER_ID, tenant(TenantStatus.ACTIVE), "EMPLOYEE");

        var claims = strict.parseToken(session.tokens().accessToken());
        assertThat(claims.keySet()).containsExactlyInAnyOrder("sub", "tenantId", "sid", "iat", "exp");
        // 30-minute access token
        assertThat(claims.getExpiration().toInstant().getEpochSecond() - claims.getIssuedAt().toInstant().getEpochSecond())
                .isEqualTo(30 * 60);
        assertThat(session.tokens().expiresIn()).isEqualTo(30 * 60);
    }

    @Test
    void sessionAndDevice_areStoredOnTheRefreshToken_andRefreshSlides365Days() {
        onboardingDone(true);
        UUID sid = UUID.randomUUID();

        var session = issuer.issue(USER_ID, tenant(TenantStatus.ACTIVE), "OWNER", sid,
                com.keevo.identity.auth.domain.model.DeviceInfo.of("dev-1", "Redmi Note 12 · Android 14"));

        assertThat(jwt.extractSessionId(jwt.parseToken(session.tokens().accessToken()))).isEqualTo(sid);
        var saved = ArgumentCaptor.forClass(RefreshToken.class);
        verify(refreshTokenRepository).save(saved.capture());
        assertThat(saved.getValue().sessionId()).isEqualTo(sid);
        assertThat(saved.getValue().deviceId()).isEqualTo("dev-1");
        assertThat(saved.getValue().deviceLabel()).isEqualTo("Redmi Note 12 · Android 14");
        assertThat(saved.getValue().lastUsedAt()).isNotNull();
        assertThat(saved.getValue().expiresAt())
                .isBetween(Instant.now().plus(364, java.time.temporal.ChronoUnit.DAYS),
                        Instant.now().plus(365, java.time.temporal.ChronoUnit.DAYS).plusSeconds(5));
    }

    @Test
    void onboardingClaim_followsTenantPreferences() {
        onboardingDone(false);

        var session = issuer.issue(USER_ID, tenant(TenantStatus.ACTIVE), "OWNER");

        assertThat(jwt.parseToken(session.tokens().accessToken()).get("onboardingCompleted"))
                .isEqualTo(false);
    }

    @Test
    void onboardingClaim_falseWhileTenantTablesAreNotProvisioned() {
        // Story 12.12: missing table is detected by the to_regclass probe — the EXISTS query
        // (which would abort the caller's Postgres transaction) is never executed.
        tablePresent(false);

        var session = issuer.issue(USER_ID, tenant(TenantStatus.ACTIVE), "OWNER");

        assertThat(jwt.parseToken(session.tokens().accessToken()).get("onboardingCompleted"))
                .isEqualTo(false);
        verify(jdbcTemplate, never()).queryForObject(contains("EXISTS"), eq(Boolean.class));
        verify(refreshTokenRepository).save(any());
    }

    @Test
    void onboardingQuery_sqlErrorIsNotSwallowed() {
        // Story 12.12: an SQL error inside the caller tx must surface, not be hidden behind a
        // "false" claim followed by a failing refresh-token save on an aborted transaction.
        tablePresent(true);
        when(jdbcTemplate.queryForObject(contains("tenant_preferences"), eq(Boolean.class)))
                .thenThrow(new BadSqlGrammarException("q", "select", new SQLException("boom")));

        assertThatThrownBy(() -> issuer.issue(USER_ID, tenant(TenantStatus.ACTIVE), "OWNER"))
                .isInstanceOf(BadSqlGrammarException.class);
        verify(refreshTokenRepository, never()).save(any());
    }

    @Test
    void persistsTheHashOfTheReturnedRefreshToken() {
        onboardingDone(true);

        var session = issuer.issue(USER_ID, tenant(TenantStatus.ACTIVE), "OWNER");

        var saved = ArgumentCaptor.forClass(RefreshToken.class);
        verify(refreshTokenRepository).save(saved.capture());
        assertThat(saved.getValue().id()).isEqualTo(session.refreshTokenId());
        assertThat(saved.getValue().tenantId()).isEqualTo(SCHEMA);
        assertThat(saved.getValue().tokenHash())
                .isEqualTo(SessionTokenIssuer.hash(session.tokens().refreshToken()))
                .isNotEqualTo(session.tokens().refreshToken());
    }
}
