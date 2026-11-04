package com.keevo.identity.auth.adapter.out.persistence.impl;

import com.keevo.identity.auth.domain.model.SocialProvider;
import com.keevo.identity.auth.domain.port.out.UserAuthenticatorRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Story 19.3 — {@code public.user_authenticators} over raw JDBC (fully-qualified table, no tenant context at sign-in).
 * One statement per method, both on the {@code (provider, subject)} unique index.
 */
@Component
public class JdbcUserAuthenticatorRepository implements UserAuthenticatorRepository {

    static final String FIND_USER_SQL =
            "SELECT user_id FROM public.user_authenticators WHERE provider = ? AND subject = ?";
    static final String INSERT_IF_ABSENT_SQL =
            "INSERT INTO public.user_authenticators (id, user_id, provider, subject, created_at) "
            + "VALUES (?, ?, ?, ?, now()) ON CONFLICT (provider, subject) DO NOTHING";

    private final JdbcTemplate jdbcTemplate;

    public JdbcUserAuthenticatorRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public Optional<UUID> findUserId(SocialProvider provider, String subject) {
        List<UUID> rows = jdbcTemplate.query(FIND_USER_SQL, (rs, n) -> (UUID) rs.getObject("user_id"),
                provider.name(), subject);
        return rows.stream().findFirst();
    }

    @Override
    public boolean insertIfAbsent(UUID userId, SocialProvider provider, String subject) {
        return jdbcTemplate.update(INSERT_IF_ABSENT_SQL, UUID.randomUUID(), userId, provider.name(), subject) == 1;
    }
}
