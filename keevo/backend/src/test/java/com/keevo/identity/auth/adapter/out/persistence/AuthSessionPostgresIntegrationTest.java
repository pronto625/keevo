package com.keevo.identity.auth.adapter.out.persistence;

import com.keevo.shared.infrastructure.persistence.PostgresIntegrationTest;
import com.keevo.identity.auth.adapter.out.persistence.impl.RefreshTokenRepositoryAdapter;
import com.keevo.identity.auth.adapter.out.persistence.jpa.PasswordResetTokenSpringRepository;
import com.keevo.identity.auth.application.service.SessionTokenIssuer;
import com.keevo.identity.auth.domain.model.PlanType;
import com.keevo.identity.auth.domain.model.Tenant;
import com.keevo.identity.auth.domain.model.TenantStatus;
import com.keevo.shared.infrastructure.security.JwtProperties;
import com.keevo.shared.infrastructure.security.JwtTokenProvider;
import com.keevo.identity.auth.adapter.out.persistence.jpa.RefreshTokenSpringRepository;
import com.keevo.sync.sync.adapter.out.persistence.impl.UserSyncStateRepositoryAdapter;
import com.keevo.sync.sync.domain.model.UserSyncState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 12.12 — runs the auth/session hardening SQL against a real PostgreSQL 16 migrated by
 * Flyway (V1–V10): atomic reset-attempt claim, CAS consumption, purge queries, guarded device
 * upsert, and the V10 phone normalisation (including the collision skip).
 */
@DataJpaTest(properties = "spring.jpa.hibernate.ddl-auto=validate")
@Import({UserSyncStateRepositoryAdapter.class, RefreshTokenRepositoryAdapter.class})
@DisplayName("Auth & session SQL on PostgreSQL (Story 12.12)")
class AuthSessionPostgresIntegrationTest extends PostgresIntegrationTest {


    @Autowired JdbcTemplate jdbc;
    @Autowired PasswordResetTokenSpringRepository resetTokens;
    @Autowired RefreshTokenSpringRepository refreshTokens;
    @Autowired UserSyncStateRepositoryAdapter syncState;
    @Autowired RefreshTokenRepositoryAdapter refreshTokenAdapter;
    @Autowired org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager entityManager;

    private static final Instant NOW = Instant.parse("2026-10-11T12:00:00Z");

    // ── password_reset_tokens ────────────────────────────────────────────────

    @Test
    @DisplayName("tryClaimAttempt: 5 claims succeed, the 6th and later are refused; counter never exceeds 5")
    void attemptClaimStopsAtFive() {
        UUID token = insertResetToken(insertUser("+237600000001"), "+237600000001", NOW.plusSeconds(600), null);

        for (int i = 1; i <= 5; i++) {
            assertThat(resetTokens.tryClaimAttempt(token, 5)).as("claim %d", i).isEqualTo(1);
        }
        assertThat(resetTokens.tryClaimAttempt(token, 5)).isZero();
        assertThat(resetTokens.tryClaimAttempt(token, 5)).isZero();
        assertThat(jdbc.queryForObject("SELECT attempts FROM public.password_reset_tokens WHERE id = ?",
                Integer.class, token)).isEqualTo(5);
    }

    @Test
    @DisplayName("tryClaimAttempt: refused on a consumed token")
    void attemptClaimRefusedWhenConsumed() {
        UUID token = insertResetToken(insertUser("+237600000002"), "+237600000002", NOW.plusSeconds(600), NOW);

        assertThat(resetTokens.tryClaimAttempt(token, 5)).isZero();
    }

    @Test
    @DisplayName("markConsumed: compare-and-set — first call wins, second loses")
    void consumeOnce() {
        UUID token = insertResetToken(insertUser("+237600000003"), "+237600000003", NOW.plusSeconds(600), null);

        assertThat(resetTokens.markConsumed(token, NOW)).isEqualTo(1);
        assertThat(resetTokens.markConsumed(token, NOW.plusSeconds(1))).isZero();
        assertThat(jdbc.queryForObject("SELECT consumed_at FROM public.password_reset_tokens WHERE id = ?",
                Timestamp.class, token).toInstant()).isEqualTo(NOW);
    }

    @Test
    @DisplayName("purgeExpiredBefore (reset tokens): deletes only rows expired before the cutoff")
    void purgeResetTokens() {
        UUID user = insertUser("+237600000004");
        UUID old = insertResetToken(user, "+237600000004", NOW.minusSeconds(3 * 86400), NOW.minusSeconds(3 * 86400));
        UUID recent = insertResetToken(user, "+237600000004", NOW.plusSeconds(600), null);

        assertThat(resetTokens.purgeExpiredBefore(NOW.minusSeconds(86400))).isEqualTo(1);
        assertThat(ids("public.password_reset_tokens")).contains(recent).doesNotContain(old);
    }

    // ── refresh_tokens ───────────────────────────────────────────────────────

    @Test
    @DisplayName("purgeExpiredBefore (refresh tokens): deletes expired rotated rows, keeps live ones")
    void purgeRefreshTokens() {
        UUID user = UUID.randomUUID();
        UUID live = insertRefreshToken(user, NOW.plusSeconds(30L * 86400), null);
        UUID rotatedExpired = insertRefreshToken(user, NOW.minusSeconds(2 * 86400), live);

        assertThat(refreshTokens.purgeExpiredBefore(NOW.minusSeconds(86400))).isEqualTo(1);
        assertThat(ids("public.refresh_tokens")).contains(live).doesNotContain(rotatedExpired);
    }

    @Test
    @DisplayName("V10 indexes exist")
    void v10Indexes() {
        List<String> names = jdbc.queryForList(
                "SELECT indexname FROM pg_indexes WHERE schemaname = 'public'", String.class);
        assertThat(names).contains(
                "idx_password_reset_tokens_phone_active",
                "idx_password_reset_tokens_expires_at",
                "idx_refresh_tokens_user_id",
                "idx_refresh_tokens_replaced_by_id",
                "idx_refresh_tokens_expires_at");
    }

    // ── user_sync_state guarded upsert ───────────────────────────────────────

    @Test
    @DisplayName("guarded upsert: owner can write, another user cannot take the device over")
    void guardedDeviceUpsert() {
        String device = UUID.randomUUID().toString();
        UUID owner = UUID.randomUUID();
        UUID intruder = UUID.randomUUID();

        assertThat(syncState.upsertOnPull(state(device, owner))).isTrue();   // insert
        assertThat(syncState.upsert(state(device, owner))).isTrue();         // same owner update
        assertThat(syncState.upsert(state(device, intruder))).isFalse();     // guarded
        assertThat(syncState.upsertOnPull(state(device, intruder))).isFalse();

        assertThat(syncState.findByDeviceId(device)).get()
                .extracting(UserSyncState::userId).isEqualTo(owner);
    }

    // ── V10 phone normalisation ──────────────────────────────────────────────

    @Test
    @DisplayName("V10: '+' prefixed on digit-only phones; a row whose '+' form already exists is skipped")
    void v10PhoneNormalisation() {
        UUID plain = insertUser("237611111111");
        UUID collidingPlain = insertUser("237622222222");
        UUID collidingPlus = insertUser("+237622222222");
        UUID alreadyCanonical = insertUser("+237633333333");
        UUID resetToken = insertResetToken(plain, "237611111111", NOW.plusSeconds(600), null);

        ScriptUtils.executeSqlScript(
                // transaction-bound connection: sees this test's uncommitted rows
                org.springframework.jdbc.datasource.DataSourceUtils.getConnection(jdbc.getDataSource()),
                new ClassPathResource("db/migration/V10__auth_token_hygiene.sql"));

        assertThat(phoneOf(plain)).isEqualTo("+237611111111");
        assertThat(phoneOf(collidingPlain)).isEqualTo("237622222222"); // skipped — manual merge
        assertThat(phoneOf(collidingPlus)).isEqualTo("+237622222222");
        assertThat(phoneOf(alreadyCanonical)).isEqualTo("+237633333333");
        assertThat(jdbc.queryForObject("SELECT phone_number FROM public.password_reset_tokens WHERE id = ?",
                String.class, resetToken)).isEqualTo("+237611111111");
    }

    // ── SessionTokenIssuer to_regclass probe (inside the caller transaction) ──

    @Test
    @DisplayName("to_regclass probe: schema without tenant_preferences → onboarding false, no SQL error, "
            + "refresh-token write in the same transaction succeeds")
    void onboardingProbeWithoutTable() throws Exception {
        jdbc.execute("CREATE SCHEMA IF NOT EXISTS \"kv_nopref\"");
        var issuer = issuer();

        var session = issuer.issue(UUID.randomUUID(), tenant("kv_nopref"), "OWNER");

        assertThat(session.tokens().accessToken()).isNotBlank();
        // follow-up writes in the same (still healthy) transaction: flush the refresh-token insert
        entityManager.flush();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM public.refresh_tokens WHERE id = ?",
                Long.class, session.refreshTokenId())).isEqualTo(1L);
        insertUser("+237600000099");
        assertThat(jdbc.queryForObject("SELECT to_regclass(?) IS NOT NULL", Boolean.class,
                "\"kv_nopref\".tenant_preferences")).isFalse();
    }

    @Test
    @DisplayName("to_regclass probe: existing \"kv_xxxxxx\".tenant_preferences → true, onboarding follows its rows")
    void onboardingProbeWithTable() throws Exception {
        jdbc.execute("CREATE SCHEMA IF NOT EXISTS \"kv_haspre\"");
        jdbc.execute("CREATE TABLE IF NOT EXISTS \"kv_haspre\".tenant_preferences (id uuid PRIMARY KEY)");
        assertThat(jdbc.queryForObject("SELECT to_regclass(?) IS NOT NULL", Boolean.class,
                "\"kv_haspre\".tenant_preferences")).isTrue();
        var issuer = issuer();
        JwtTokenProvider jwt = jwtProvider;

        var empty = issuer.issue(UUID.randomUUID(), tenant("kv_haspre"), "OWNER");
        assertThat(jwt.parseToken(empty.tokens().accessToken()).get("onboardingCompleted")).isEqualTo(false);

        jdbc.update("INSERT INTO \"kv_haspre\".tenant_preferences (id) VALUES (?)", UUID.randomUUID());
        var done = issuer.issue(UUID.randomUUID(), tenant("kv_haspre"), "OWNER");
        assertThat(jwt.parseToken(done.tokens().accessToken()).get("onboardingCompleted")).isEqualTo(true);
    }

    private JwtTokenProvider jwtProvider;

    private SessionTokenIssuer issuer() throws Exception {
        KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(2048);
        KeyPair kp = gen.generateKeyPair();
        JwtProperties props = new JwtProperties(24, 30);
        jwtProvider = new JwtTokenProvider((RSAPrivateKey) kp.getPrivate(), (RSAPublicKey) kp.getPublic(), props);
        return new SessionTokenIssuer(jwtProvider, props, refreshTokenAdapter, jdbc);
    }

    private static Tenant tenant(String schema) {
        return new Tenant(UUID.randomUUID(), "KV-" + schema.substring(3).toUpperCase(), schema,
                TenantStatus.ACTIVE, PlanType.FREE, NOW);
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private UUID insertUser(String phone) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO public.users (id, created_at, updated_at, is_active, failed_attempts, "
                        + "password_hash, phone_number, role) VALUES (?, ?, ?, true, 0, 'x', ?, 'OWNER')",
                id, Timestamp.from(NOW), Timestamp.from(NOW), phone);
        return id;
    }

    private UUID insertResetToken(UUID userId, String phone, Instant expiresAt, Instant consumedAt) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO public.password_reset_tokens (id, user_id, phone_number, code_hash, expires_at, "
                        + "consumed_at, attempts, created_at, updated_at) VALUES (?, ?, ?, 'h', ?, ?, 0, ?, ?)",
                id, userId, phone, Timestamp.from(expiresAt),
                consumedAt != null ? Timestamp.from(consumedAt) : null,
                Timestamp.from(NOW), Timestamp.from(NOW));
        return id;
    }

    private UUID insertRefreshToken(UUID userId, Instant expiresAt, UUID replacedBy) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO public.refresh_tokens (id, created_at, updated_at, expires_at, revoked, tenant_id, "
                        + "token_hash, user_id, replaced_by_id) VALUES (?, ?, ?, ?, ?, 'kv_abc123', ?, ?, ?)",
                id, Timestamp.from(NOW), Timestamp.from(NOW), Timestamp.from(expiresAt), replacedBy != null,
                UUID.randomUUID().toString(), userId, replacedBy);
        return id;
    }

    private List<UUID> ids(String table) {
        return jdbc.queryForList("SELECT id FROM " + table, UUID.class);
    }

    private String phoneOf(UUID userId) {
        return jdbc.queryForObject("SELECT phone_number FROM public.users WHERE id = ?", String.class, userId);
    }

    private static UserSyncState state(String device, UUID user) {
        return new UserSyncState(device, user, "kv_abc123", null, null, null);
    }
}
