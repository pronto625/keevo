package com.keevo.identity.auth.adapter.out.persistence;

import com.keevo.identity.auth.adapter.out.persistence.impl.JdbcOtpChallengeRepository;
import com.keevo.identity.auth.adapter.out.persistence.impl.UserRepositoryAdapter;
import com.keevo.identity.auth.domain.model.User;
import com.keevo.shared.infrastructure.persistence.PostgresIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 19.2 — the real SQL of the sign-in code challenges and of the conflict-safe account insert, on PostgreSQL.
 * {@code @DataJpaTest} runs each test in one transaction and rolls it back.
 */
@DataJpaTest(properties = "spring.jpa.hibernate.ddl-auto=validate")
@Import({JdbcOtpChallengeRepository.class, UserRepositoryAdapter.class})
@DisplayName("19.2 — OTP challenges and account insert on PostgreSQL")
class OtpPersistencePostgresIntegrationTest extends PostgresIntegrationTest {

    private static final Instant NOW = Instant.parse("2026-11-04T10:00:00Z");

    @Autowired JdbcTemplate jdbc;
    @Autowired JdbcOtpChallengeRepository challenges;
    @Autowired UserRepositoryAdapter users;

    private static String id() {
        return "otp-" + UUID.randomUUID().toString().substring(0, 8) + "@ex.com";
    }

    private UUID challenge(String identifier, Instant createdAt, int failures) {
        UUID id = UUID.randomUUID();
        challenges.insert(id, identifier, "$2a$12$hash", createdAt.plus(Duration.ofMinutes(5)), createdAt);
        for (int i = 0; i < failures; i++) {
            challenges.recordFailure(id, 5, NOW);
        }
        return id;
    }

    @Test
    @DisplayName("sumFailuresSince sums attempts over the identifier's challenges since the cutoff only")
    void sumFailuresSince() {
        String a = id();
        String b = id();
        Instant since = NOW.minus(Duration.ofHours(24));
        challenge(a, NOW.minus(Duration.ofHours(1)), 3);
        challenge(a, NOW.minus(Duration.ofHours(5)), 4);
        challenge(a, NOW.minus(Duration.ofHours(30)), 5);   // older than since: ignored
        challenge(b, NOW.minus(Duration.ofHours(1)), 2);    // other identifier: ignored

        assertThat(challenges.sumFailuresSince(a, since)).isEqualTo(7);
        assertThat(challenges.sumFailuresSince(b, since)).isEqualTo(2);
        assertThat(challenges.sumFailuresSince(id(), since)).isZero();
    }

    @Test
    @DisplayName("recordFailure consumes the challenge at the 5th wrong code; lockLatestActive then skips it")
    void recordFailureConsumesAtMax() {
        String a = id();
        UUID c = challenge(a, NOW.minus(Duration.ofMinutes(1)), 4);
        assertThat(challenges.lockLatestActive(a, NOW)).map(x -> x.id()).contains(c);
        assertThat(challenges.recordFailure(c, 5, NOW)).isEqualTo(5);
        assertThat(challenges.lockLatestActive(a, NOW)).isEmpty();
    }

    @Test
    @DisplayName("purgeCreatedBefore deletes only rows created before the cutoff")
    void purge() {
        String a = id();
        Instant cutoff = NOW.minus(Duration.ofHours(24));
        UUID old = challenge(a, cutoff.minusSeconds(1), 0);
        UUID kept = challenge(a, cutoff.plusSeconds(1), 0);

        assertThat(challenges.purgeCreatedBefore(cutoff)).isGreaterThanOrEqualTo(1);
        assertThat(jdbc.queryForList("SELECT id FROM public.otp_challenges WHERE identifier = ?", UUID.class, a))
                .containsExactly(kept).doesNotContain(old);
    }

    @Test
    @DisplayName("insertIfAbsent: an existing Ana@Ex.com blocks a new ana@ex.com, row unchanged, transaction usable")
    void insertIfAbsentConflict() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String upper = "Ana-" + suffix + "@Ex.com";
        UUID existing = UUID.randomUUID();
        jdbc.update("INSERT INTO public.users (id, email, role, is_active, failed_attempts, created_at, updated_at) "
                + "VALUES (?, ?, 'OWNER', true, 0, now(), now())", existing, upper);

        User candidate = User.newOtpAccount(null, upper.toLowerCase(), NOW);
        assertThat(users.insertIfAbsent(candidate)).isFalse();

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT id, email, email_verified_at FROM public.users WHERE lower(email) = lower(?)", upper);
        assertThat(row.get("id")).isEqualTo(existing);
        assertThat(row.get("email")).isEqualTo(upper);
        assertThat(row.get("email_verified_at")).isNull();
        assertThat(users.findById(candidate.getId())).isEmpty();
        assertThat(users.findByEmail(upper.toLowerCase())).map(User::getId).contains(existing);

        User fresh = User.newOtpAccount(null, "new-" + suffix + "@ex.com", NOW);
        assertThat(users.insertIfAbsent(fresh)).isTrue();
        assertThat(users.findByEmail("NEW-" + suffix + "@EX.COM")).map(User::getId).contains(fresh.getId());
    }
}
