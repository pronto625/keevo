package com.keevo.identity.auth.adapter.out.persistence.impl;

import com.keevo.identity.auth.domain.model.SocialProvider;
import com.keevo.identity.auth.domain.port.out.UserAuthenticatorRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Story 19.3 — {@code public.user_authenticators} over raw JDBC (fully-qualified table, no tenant context at sign-in).
 * One statement per method (two for {@link #unlink}), on the {@code (provider, subject)} unique index or the
 * {@code user_id} index.
 */
@Component
public class JdbcUserAuthenticatorRepository implements UserAuthenticatorRepository {

    static final String FIND_USER_SQL =
            "SELECT user_id FROM public.user_authenticators WHERE provider = ? AND subject = ?";
    static final String INSERT_IF_ABSENT_SQL =
            "INSERT INTO public.user_authenticators (id, user_id, provider, subject, created_at) "
            + "VALUES (?, ?, ?, ?, now()) ON CONFLICT (provider, subject) DO NOTHING";
    static final String FIND_BY_USER_SQL =
            "SELECT id, provider, subject, created_at FROM public.user_authenticators WHERE user_id = ? "
            + "ORDER BY created_at, id";
    static final String FIND_ID_SQL =
            "SELECT id FROM public.user_authenticators WHERE provider = ? AND subject = ?";
    static final String STORE_TOKEN_SQL =
            "UPDATE public.user_authenticators SET revocation_token = ?, revocation_client_id = ? WHERE id = ?";
    /** Story 19.3b — outbox copy, in the transaction of the DELETE below. */
    static final String QUEUE_REVOCATIONS_SQL =
            "INSERT INTO public.apple_token_revocations (authenticator_id, client_id, token) "
            + "SELECT id, revocation_client_id, revocation_token FROM public.user_authenticators "
            + "WHERE user_id = ? AND provider = ? AND revocation_token IS NOT NULL AND revocation_client_id IS NOT NULL";
    static final String UNLINK_SQL =
            "DELETE FROM public.user_authenticators WHERE user_id = ? AND provider = ?";

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

    @Override
    public List<LinkedIdentity> findByUser(UUID userId) {
        return jdbcTemplate.query(FIND_BY_USER_SQL, (rs, n) -> new LinkedIdentity(
                (UUID) rs.getObject("id"),
                SocialProvider.valueOf(rs.getString("provider")),
                rs.getString("subject"),
                rs.getObject("created_at", Timestamp.class).toInstant()), userId);
    }

    @Override
    public Optional<UUID> findId(SocialProvider provider, String subject) {
        List<UUID> rows = jdbcTemplate.query(FIND_ID_SQL, (rs, n) -> (UUID) rs.getObject("id"),
                provider.name(), subject);
        return rows.stream().findFirst();
    }

    @Override
    public boolean storeRevocationToken(UUID authenticatorId, String clientId, byte[] encryptedToken) {
        return jdbcTemplate.update(STORE_TOKEN_SQL, encryptedToken, clientId, authenticatorId) == 1;
    }

    @Override
    public int unlink(UUID userId, SocialProvider provider) {
        jdbcTemplate.update(QUEUE_REVOCATIONS_SQL, userId, provider.name());
        return jdbcTemplate.update(UNLINK_SQL, userId, provider.name());
    }
}
