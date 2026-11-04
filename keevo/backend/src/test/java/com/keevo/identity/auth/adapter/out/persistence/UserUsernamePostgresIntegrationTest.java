package com.keevo.identity.auth.adapter.out.persistence;

import com.keevo.identity.auth.adapter.out.persistence.impl.UserRepositoryAdapter;
import com.keevo.identity.auth.domain.model.User;
import com.keevo.shared.infrastructure.persistence.PostgresIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 19.4 — the real SQL of the username lookup and of the conflict-safe member insert on PostgreSQL:
 * {@code lower(username) = lower(?)} on {@code uq_users_username_lower}, and a concurrent creation of the same username
 * in another case leaving the row out instead of failing. {@code @DataJpaTest} rolls each test back.
 */
@DataJpaTest(properties = "spring.jpa.hibernate.ddl-auto=validate")
@Import(UserRepositoryAdapter.class)
@DisplayName("19.4 — username lookup and member insert on PostgreSQL")
class UserUsernamePostgresIntegrationTest extends PostgresIntegrationTest {

    @Autowired JdbcTemplate jdbc;
    @Autowired UserRepositoryAdapter users;

    private static String suffix() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    @Test
    @DisplayName("findByUsername is case-insensitive and stripped; null / blank / unknown → empty")
    void findByUsernameCaseInsensitive() {
        String typed = "Jean.Dupont-" + suffix();
        User member = User.newEmployee(null, typed, "$2b$12$hash");
        assertThat(users.insertIfAbsent(member)).isTrue();

        assertThat(users.findByUsername(typed.toLowerCase())).map(User::getId).contains(member.getId());
        assertThat(users.findByUsername("  " + typed.toUpperCase() + " ")).map(User::getId).contains(member.getId());
        assertThat(users.findByUsername(typed)).map(User::getUsername).contains(typed); // stored as typed
        assertThat(users.findByUsername(typed)).map(User::getPhoneNumber).isEmpty();
        assertThat(users.findByUsername("nobody-" + suffix())).isEmpty();
        assertThat(users.findByUsername(null)).isEmpty();
        assertThat(users.findByUsername("  ")).isEmpty();
    }

    @Test
    @DisplayName("an all-digit username is never found by the phone lookup, nor a phone by the username lookup")
    void digitsNeverConfused() {
        String unsigned = Long.toUnsignedString(UUID.randomUUID().getMostSignificantBits());
        String digits = "6" + unsigned.substring(unsigned.length() - 8);
        User member = User.newEmployee(null, digits, "$2b$12$hash");
        assertThat(users.insertIfAbsent(member)).isTrue();

        assertThat(users.findByPhoneNumber(digits)).isEmpty();
        assertThat(users.findByPhoneNumber("+" + digits)).isEmpty();
        assertThat(users.findByUsername(digits)).map(User::getId).contains(member.getId());
    }

    @Test
    @DisplayName("findAllByIds loads several users in one query; unknown ids are absent; empty → empty")
    void findAllByIds() {
        User a = User.newEmployee(null, "bulk_a" + suffix(), "$2b$12$hash");
        User b = User.newEmployee(null, "bulk_b" + suffix(), "$2b$12$hash");
        assertThat(users.insertIfAbsent(a)).isTrue();
        assertThat(users.insertIfAbsent(b)).isTrue();

        assertThat(users.findAllByIds(java.util.List.of(a.getId(), b.getId(), UUID.randomUUID(), a.getId())))
                .extracting(User::getId).containsExactlyInAnyOrder(a.getId(), b.getId());
        assertThat(users.findAllByIds(java.util.List.of())).isEmpty();
    }

    @Test
    @DisplayName("concurrent creation: AWA_K existing → a new awa_k insert is left out (index hit), transaction usable")
    void uniqueIndexRace() {
        String upper = "AWA_K" + suffix().toUpperCase();
        UUID existing = UUID.randomUUID();
        jdbc.update("INSERT INTO public.users (id, username, password_hash, role, is_active, failed_attempts, "
                + "created_at, updated_at) VALUES (?, ?, '$2b$12$x', 'EMPLOYEE', true, 0, now(), now())", existing, upper);

        User candidate = User.newEmployee(null, upper.toLowerCase(), "$2a$12$y");
        assertThat(users.insertIfAbsent(candidate)).isFalse();

        assertThat(users.findById(candidate.getId())).isEmpty();
        assertThat(users.findByUsername(upper.toLowerCase())).map(User::getId).contains(existing);
        User other = User.newEmployee(null, "other_" + suffix(), "$2a$12$y");
        assertThat(users.insertIfAbsent(other)).isTrue(); // the transaction is still usable
    }
}
