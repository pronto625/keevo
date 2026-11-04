package com.keevo.identity.auth.adapter.out.persistence;

import com.keevo.identity.auth.adapter.out.persistence.impl.JdbcUserAuthenticatorRepository;
import com.keevo.identity.auth.adapter.out.persistence.impl.UserRepositoryAdapter;
import com.keevo.identity.auth.application.service.SocialSignInService;
import com.keevo.identity.auth.domain.model.SocialIdentity;
import com.keevo.identity.auth.domain.model.SocialProvider;
import com.keevo.identity.auth.domain.model.User;
import com.keevo.shared.infrastructure.persistence.PostgresIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 19.3 — {@code public.user_authenticators} (V48) and the social account insert on PostgreSQL: unique
 * {@code (provider, subject)} conflict, cascade delete with the account, provider CHECK, and an account identified only
 * by its technical username against the V41 CHECKs. {@code @DataJpaTest} rolls each test back.
 */
@DataJpaTest(properties = "spring.jpa.hibernate.ddl-auto=validate")
@Import({JdbcUserAuthenticatorRepository.class, UserRepositoryAdapter.class})
@DisplayName("19.3 — user authenticators and social accounts on PostgreSQL")
class UserAuthenticatorPostgresIntegrationTest extends PostgresIntegrationTest {

    private static final Instant NOW = Instant.parse("2026-11-04T10:00:00Z");

    @Autowired JdbcTemplate jdbc;
    @Autowired JdbcUserAuthenticatorRepository authenticators;
    @Autowired UserRepositoryAdapter users;

    private static String sub() {
        return "sub-" + UUID.randomUUID();
    }

    private User emailAccount() {
        User u = User.newSocialAccount("soc-" + UUID.randomUUID().toString().substring(0, 8) + "@ex.com", null,
                "Ana", null, NOW);
        assertThat(users.insertIfAbsent(u)).isTrue();
        return u;
    }

    private static String technicalUsername(String subject, int hex) {
        return SocialSignInService.technicalUsername(
                new SocialIdentity(SocialProvider.APPLE, subject, null, false, null, null), hex);
    }

    @Test
    @DisplayName("insertIfAbsent then findUserId; a second insert of the same (provider, subject) is a no-op")
    void uniqueConflict() {
        User a = emailAccount();
        User b = emailAccount();
        String subject = sub();

        assertThat(authenticators.insertIfAbsent(a.getId(), SocialProvider.GOOGLE, subject)).isTrue();
        assertThat(authenticators.insertIfAbsent(b.getId(), SocialProvider.GOOGLE, subject)).isFalse();
        assertThat(authenticators.findUserId(SocialProvider.GOOGLE, subject)).contains(a.getId());
        // Same subject at another provider is another identity.
        assertThat(authenticators.insertIfAbsent(b.getId(), SocialProvider.APPLE, subject)).isTrue();
        assertThat(authenticators.findUserId(SocialProvider.APPLE, subject)).contains(b.getId());
        assertThat(authenticators.findUserId(SocialProvider.APPLE, sub())).isEmpty();
    }

    @Test
    @DisplayName("one account can hold Google and Apple; deleting the account deletes its authenticators")
    void twoProvidersAndCascade() {
        User a = emailAccount();
        authenticators.insertIfAbsent(a.getId(), SocialProvider.GOOGLE, sub());
        authenticators.insertIfAbsent(a.getId(), SocialProvider.APPLE, sub());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM public.user_authenticators WHERE user_id = ?",
                Integer.class, a.getId())).isEqualTo(2);

        jdbc.update("DELETE FROM public.users WHERE id = ?", a.getId());

        assertThat(jdbc.queryForObject("SELECT count(*) FROM public.user_authenticators WHERE user_id = ?",
                Integer.class, a.getId())).isZero();
    }

    @Test
    @DisplayName("account with a technical username only passes the V41 CHECKs; same username again conflicts")
    void technicalUsernameAccount() {
        String subject = "001234." + UUID.randomUUID().toString().replace("-", "") + ".0042";
        String username = technicalUsername(subject, 12);
        User account = User.newSocialAccount(null, username, null, null, NOW);

        assertThat(users.insertIfAbsent(account)).isTrue();
        var row = jdbc.queryForMap("SELECT phone_number, email, username, password_hash, role, email_verified_at "
                + "FROM public.users WHERE id = ?", account.getId());
        assertThat(row.get("phone_number")).isNull();
        assertThat(row.get("email")).isNull();
        assertThat(row.get("password_hash")).isNull();
        assertThat(row.get("email_verified_at")).isNull();
        assertThat(row.get("username")).isEqualTo(username);
        assertThat(row.get("role")).isEqualTo("OWNER");
        assertThat(authenticators.insertIfAbsent(account.getId(), SocialProvider.APPLE, subject)).isTrue();

        // Concurrent first login of the same subject derives the same username → the insert is a no-op.
        User twin = User.newSocialAccount(null, username.toUpperCase(), null, null, NOW);
        assertThat(users.insertIfAbsent(twin)).isFalse();
        // The longest suffix still satisfies users_username_format (≤ 50 chars).
        assertThat(users.insertIfAbsent(User.newSocialAccount(null, technicalUsername(subject, 36), null, null,
                NOW))).isTrue();
    }

    @Test
    @DisplayName("provider CHECK refuses an unknown provider")
    void providerCheck() {
        User a = emailAccount();
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO public.user_authenticators (id, user_id, provider, subject) VALUES (?, ?, 'FACEBOOK', 'x')",
                UUID.randomUUID(), a.getId()))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("setEmailIfAbsent: sets email + verified date once; taken by another account or already set → 0 rows")
    void conditionalEmailUpdate() {
        User taken = emailAccount();
        User noEmail = User.newSocialAccount(null, "apple-" + UUID.randomUUID().toString().substring(0, 12)
                .replace("-", "0"), null, null, NOW);
        assertThat(users.insertIfAbsent(noEmail)).isTrue();

        assertThat(users.setEmailIfAbsent(noEmail.getId(), taken.getEmail().toUpperCase(), NOW)).isZero();
        assertThat(jdbc.queryForObject("SELECT email FROM public.users WHERE id = ?", String.class, noEmail.getId()))
                .isNull();

        String fresh = "new-" + UUID.randomUUID().toString().substring(0, 8) + "@ex.com";
        assertThat(users.setEmailIfAbsent(noEmail.getId(), fresh, NOW)).isEqualTo(1);
        var row = jdbc.queryForMap("SELECT email, email_verified_at FROM public.users WHERE id = ?", noEmail.getId());
        assertThat(row.get("email")).isEqualTo(fresh);
        assertThat(row.get("email_verified_at")).isNotNull();

        assertThat(users.setEmailIfAbsent(noEmail.getId(), "other-" + fresh, NOW)).isZero();
    }
}
