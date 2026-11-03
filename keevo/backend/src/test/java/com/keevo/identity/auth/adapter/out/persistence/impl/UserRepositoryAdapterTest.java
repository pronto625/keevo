package com.keevo.identity.auth.adapter.out.persistence.impl;

import com.keevo.identity.api.Role;
import com.keevo.identity.auth.adapter.out.persistence.entity.UserJpaEntity;
import com.keevo.identity.auth.adapter.out.persistence.jpa.UserSpringRepository;
import com.keevo.identity.auth.domain.model.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * Story 19.1 — null-safe phone lookups (no query for a null/blank phone, which Spring Data would turn into
 * {@code phone_number IS NULL}) and the domain ↔ entity mapping of the new columns.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("UserRepositoryAdapter (19.1)")
class UserRepositoryAdapterTest {

    @Mock private UserSpringRepository springRepository;
    @Mock private JdbcTemplate jdbcTemplate;

    private UserRepositoryAdapter adapter;

    @BeforeEach
    void setUp() {
        adapter = new UserRepositoryAdapter(springRepository, jdbcTemplate);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   "})
    @DisplayName("null / blank phone: empty and false, no query")
    void nullOrBlankPhone_noQuery(String phone) {
        assertThat(adapter.findByPhoneNumber(phone)).isEmpty();
        assertThat(adapter.existsByPhoneNumber(phone)).isFalse();
        verifyNoInteractions(springRepository, jdbcTemplate);
    }

    @Test
    @DisplayName("mapping keeps the identifiers, the profile and a missing phone / password both ways")
    void mapping_roundTrip() {
        Instant t0 = Instant.parse("2026-11-03T08:00:00Z");
        User emailOnly = new User(UUID.randomUUID(), null, null, Role.OWNER, true, t0, 1, null,
                "Awa@Example.cm", "awa", "Awa", "Ndiaye", t0, t0.plusSeconds(5));

        UserJpaEntity e = UserRepositoryAdapter.toEntity(emailOnly);
        assertThat(e.getPhoneNumber()).isNull();
        assertThat(e.getPasswordHash()).isNull();
        assertThat(e.getEmail()).isEqualTo("Awa@Example.cm");
        assertThat(e.getUsername()).isEqualTo("awa");
        assertThat(e.getFirstName()).isEqualTo("Awa");
        assertThat(e.getLastName()).isEqualTo("Ndiaye");
        assertThat(e.getEmailVerifiedAt()).isEqualTo(t0);
        assertThat(e.getPhoneVerifiedAt()).isEqualTo(t0.plusSeconds(5));

        User back = UserRepositoryAdapter.toDomain(e);
        assertThat(back.getId()).isEqualTo(emailOnly.getId());
        assertThat(back.getPhoneNumber()).isNull();
        assertThat(back.getPasswordHash()).isNull();
        assertThat(back.getEmail()).isEqualTo("Awa@Example.cm");
        assertThat(back.getUsername()).isEqualTo("awa");
        assertThat(back.getFirstName()).isEqualTo("Awa");
        assertThat(back.getLastName()).isEqualTo("Ndiaye");
        assertThat(back.getEmailVerifiedAt()).isEqualTo(t0);
        assertThat(back.getPhoneVerifiedAt()).isEqualTo(t0.plusSeconds(5));
        assertThat(back.failedAttempts()).isEqualTo(1);
    }
}
