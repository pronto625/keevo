package com.keevo.identity.auth.adapter.out.persistence.impl;

import com.keevo.identity.auth.domain.model.SocialProvider;
import com.keevo.identity.auth.domain.port.out.SocialNonceRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Instant;

/**
 * Story 19.3b — {@code public.social_nonces}: insert on the primary key (one-time use), purge on the
 * {@code expires_at} index.
 */
@Component
public class JdbcSocialNonceRepository implements SocialNonceRepository {

    static final String CONSUME_SQL =
            "INSERT INTO public.social_nonces (provider, nonce, expires_at) VALUES (?, ?, ?) "
            + "ON CONFLICT (provider, nonce) DO NOTHING";
    static final String PURGE_SQL = "DELETE FROM public.social_nonces WHERE expires_at < ?";

    private final JdbcTemplate jdbcTemplate;

    public JdbcSocialNonceRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public boolean consume(SocialProvider provider, String nonce, Instant expiresAt) {
        return jdbcTemplate.update(CONSUME_SQL, provider.name(), nonce, Timestamp.from(expiresAt)) == 1;
    }

    @Override
    public int purgeExpiredBefore(Instant cutoff) {
        return jdbcTemplate.update(PURGE_SQL, Timestamp.from(cutoff));
    }
}
