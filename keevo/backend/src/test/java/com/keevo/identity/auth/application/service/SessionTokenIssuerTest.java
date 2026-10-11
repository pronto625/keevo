package com.keevo.identity.auth.application.service;

import com.keevo.identity.auth.domain.model.PlanType;
import com.keevo.identity.auth.domain.model.RefreshToken;
import com.keevo.identity.auth.domain.model.Tenant;
import com.keevo.identity.auth.domain.model.TenantStatus;
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
        JwtProperties props = new JwtProperties(24, 360);
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
    void employeeToken_carriesStoreAndRealTenantStatus() {
        UUID store = UUID.randomUUID();
        onboardingDone(true);
        when(jdbcTemplate.queryForMap(contains("employees"), eq(USER_ID))).thenReturn(Map.of(
                "store_id", store, "password_change_required", true, "first_name", "Loïc"));

        var session = issuer.issue(USER_ID, tenant(TenantStatus.SUSPENDED), "EMPLOYEE");

        var claims = jwt.parseToken(session.tokens().accessToken());
        assertThat(jwt.extractStoreId(claims)).isEqualTo(store);
        assertThat(jwt.extractTenantStatus(claims)).isEqualTo("SUSPENDED");
        assertThat(jwt.extractPasswordChangeRequired(claims)).isTrue();
        assertThat(jwt.extractFirstName(claims)).isEqualTo("Loïc");
        assertThat(jwt.extractRole(claims)).isEqualTo("EMPLOYEE");
        assertThat(session.tokens().storeId()).isEqualTo(store.toString());
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
