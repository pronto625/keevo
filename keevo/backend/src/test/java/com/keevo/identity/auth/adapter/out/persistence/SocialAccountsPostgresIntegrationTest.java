package com.keevo.identity.auth.adapter.out.persistence;

import com.keevo.identity.auth.adapter.out.persistence.impl.JdbcAppleRevocationQueue;
import com.keevo.identity.auth.adapter.out.persistence.impl.JdbcSocialNonceRepository;
import com.keevo.identity.auth.adapter.out.persistence.impl.JdbcUserAuthenticatorRepository;
import com.keevo.identity.auth.adapter.out.persistence.impl.UserRepositoryAdapter;
import com.keevo.identity.auth.domain.model.SocialProvider;
import com.keevo.identity.auth.domain.model.User;
import com.keevo.identity.auth.domain.port.out.AppleRevocationQueue.PendingRevocation;
import com.keevo.identity.auth.domain.port.out.UserAuthenticatorRepository.LinkedIdentity;
import com.keevo.shared.infrastructure.persistence.PostgresIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 19.3b — V73 on PostgreSQL: one-time nonces, linked identities listing, unlink with the revocation outbox
 * copy, outbox draining (due order, delete, backoff, give-up). {@code @DataJpaTest} rolls each test back.
 */
@DataJpaTest(properties = "spring.jpa.hibernate.ddl-auto=validate")
@Import({JdbcUserAuthenticatorRepository.class, UserRepositoryAdapter.class, JdbcSocialNonceRepository.class,
        JdbcAppleRevocationQueue.class})
@DisplayName("19.3b — social nonces, unlink and Apple revocation outbox on PostgreSQL")
class SocialAccountsPostgresIntegrationTest extends PostgresIntegrationTest {

    private static final Instant NOW = Instant.now().truncatedTo(ChronoUnit.SECONDS);

    @Autowired JdbcTemplate jdbc;
    @Autowired JdbcUserAuthenticatorRepository authenticators;
    @Autowired UserRepositoryAdapter users;
    @Autowired JdbcSocialNonceRepository nonces;
    @Autowired JdbcAppleRevocationQueue queue;

    @BeforeEach
    void cleanQueue() {
        jdbc.update("DELETE FROM public.apple_token_revocations");
    }

    private User account() {
        User u = User.newSocialAccount("s3b-" + UUID.randomUUID().toString().substring(0, 8) + "@ex.com", null,
                null, null, NOW);
        assertThat(users.insertIfAbsent(u)).isTrue();
        return u;
    }

    private static String sub() {
        return "sub-" + UUID.randomUUID();
    }

    @Test
    @DisplayName("nonce: first consume inserts, second is a replay; same nonce at the other provider is distinct; purge")
    void nonces() {
        String n = UUID.randomUUID().toString().replace("-", "");
        assertThat(nonces.consume(SocialProvider.GOOGLE, n, NOW.plusSeconds(600))).isTrue();
        assertThat(nonces.consume(SocialProvider.GOOGLE, n, NOW.plusSeconds(600))).isFalse();
        assertThat(nonces.consume(SocialProvider.APPLE, n, NOW.minusSeconds(10))).isTrue();

        assertThat(nonces.purgeExpiredBefore(NOW)).isGreaterThanOrEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM public.social_nonces WHERE nonce = ?", Integer.class, n))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("findByUser oldest first, findId; storeRevocationToken; unlink copies stored Apple tokens then deletes")
    void unlinkQueuesRevocation() {
        User a = account();
        String apple = sub();
        authenticators.insertIfAbsent(a.getId(), SocialProvider.APPLE, apple);
        jdbc.update("UPDATE public.user_authenticators SET created_at = now() - interval '1 hour' WHERE subject = ?",
                apple);
        authenticators.insertIfAbsent(a.getId(), SocialProvider.GOOGLE, sub());

        List<LinkedIdentity> linked = authenticators.findByUser(a.getId());
        assertThat(linked).extracting(LinkedIdentity::provider)
                .containsExactly(SocialProvider.APPLE, SocialProvider.GOOGLE);
        UUID appleRow = authenticators.findId(SocialProvider.APPLE, apple).orElseThrow();
        assertThat(appleRow).isEqualTo(linked.get(0).id());
        authenticators.storeRevocationToken(appleRow, "com.keevo.app", new byte[]{1, 2, 3});

        assertThat(authenticators.unlink(a.getId(), SocialProvider.APPLE)).isEqualTo(1);

        assertThat(authenticators.findByUser(a.getId())).extracting(LinkedIdentity::provider)
                .containsExactly(SocialProvider.GOOGLE);
        List<PendingRevocation> due = queue.findDue(10);
        assertThat(due).hasSize(1);
        assertThat(due.get(0).authenticatorId()).isEqualTo(appleRow);
        assertThat(due.get(0).clientId()).isEqualTo("com.keevo.app");
        assertThat(due.get(0).token()).containsExactly(1, 2, 3);

        // Google has no stored token: unlinking it queues nothing.
        assertThat(authenticators.unlink(a.getId(), SocialProvider.GOOGLE)).isEqualTo(1);
        assertThat(queue.findDue(10)).hasSize(1);
        assertThat(authenticators.unlink(a.getId(), SocialProvider.GOOGLE)).isZero();
    }

    @Test
    @DisplayName("outbox: due rows only, oldest first; failures back off; give-up at max attempts; delete")
    void outboxDrain() {
        UUID first = insertRevocation("now() - interval '2 minutes'", 0);
        UUID second = insertRevocation("now() - interval '1 minute'", 18);
        insertRevocation("now() + interval '1 hour'", 0);

        List<PendingRevocation> due = queue.findDue(10);
        assertThat(due).extracting(PendingRevocation::id).containsExactly(first, second);
        assertThat(queue.findDue(1)).extracting(PendingRevocation::id).containsExactly(first);

        assertThat(queue.recordFailures(List.of(first, second), 19)).isEqualTo(1); // second reaches 19
        assertThat(jdbc.queryForObject("SELECT attempts FROM public.apple_token_revocations WHERE id = ?",
                Integer.class, first)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT next_attempt_at > now() + interval '90 seconds' "
                + "FROM public.apple_token_revocations WHERE id = ?", Boolean.class, first)).isTrue();
        assertThat(queue.findDue(10)).isEmpty();

        assertThat(queue.delete(List.of(first))).isEqualTo(1);
        assertThat(queue.delete(List.of())).isZero();
        assertThat(queue.recordFailures(List.of(), 20)).isZero();
    }

    private UUID insertRevocation(String nextAttemptSql, int attempts) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO public.apple_token_revocations (id, authenticator_id, client_id, token, attempts, "
                + "next_attempt_at) VALUES (?, ?, 'com.keevo.app', ?, ?, " + nextAttemptSql + ")",
                id, UUID.randomUUID(), new byte[]{9}, attempts);
        return id;
    }
}
