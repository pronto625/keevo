package com.keevo.clientportal.auth.adapter.out.persistence;

import com.keevo.clientportal.auth.domain.port.out.PortalSessionRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Story 21.7 — {@code public.portal_clients} / {@code public.portal_sessions} over raw JDBC, one statement per method
 * (upsert by the unique phone index, session lookup by primary key joined to its client by primary key, trim on
 * {@code idx_portal_sessions_client}, purge on {@code idx_portal_sessions_expires_at}).
 */
@Component
public class JdbcPortalSessionRepository implements PortalSessionRepository {

    static final String UPSERT_CLIENT_SQL =
            "INSERT INTO public.portal_clients (id, phone, created_at, last_login_at) VALUES (?, ?, ?, ?) "
            + "ON CONFLICT (phone) DO UPDATE SET last_login_at = EXCLUDED.last_login_at RETURNING id";
    static final String INSERT_SESSION_SQL =
            "INSERT INTO public.portal_sessions (token_hash, client_id, created_at, expires_at) VALUES (?, ?, ?, ?)";
    static final String TRIM_SQL =
            "DELETE FROM public.portal_sessions WHERE client_id = ? AND token_hash NOT IN (SELECT token_hash FROM "
            + "public.portal_sessions WHERE client_id = ? ORDER BY created_at DESC, token_hash DESC LIMIT ?)";
    static final String PHONE_SQL =
            "SELECT c.phone FROM public.portal_sessions s JOIN public.portal_clients c ON c.id = s.client_id "
            + "WHERE s.token_hash = ? AND s.expires_at > ?";
    static final String DELETE_SQL = "DELETE FROM public.portal_sessions WHERE token_hash = ?";
    static final String PURGE_SQL = "DELETE FROM public.portal_sessions WHERE expires_at < ?";
    static final String BY_PHONE_SQL = "SELECT id, phone, password_hash FROM public.portal_clients WHERE phone = ?";
    static final String BY_EMAIL_SQL = "SELECT id, phone, password_hash FROM public.portal_clients WHERE email = ?";
    static final String EMAIL_TAKEN_SQL =
            "SELECT EXISTS (SELECT 1 FROM public.portal_clients WHERE email = ? AND phone <> ?)";
    static final String REGISTER_SQL =
            "INSERT INTO public.portal_clients (id, phone, name, email, password_hash, created_at, last_login_at) "
            + "VALUES (?, ?, ?, ?, ?, ?, ?) ON CONFLICT (phone) DO UPDATE SET name = EXCLUDED.name, "
            + "email = COALESCE(EXCLUDED.email, portal_clients.email), password_hash = EXCLUDED.password_hash, "
            + "last_login_at = EXCLUDED.last_login_at RETURNING id";
    static final String TOUCH_SQL = "UPDATE public.portal_clients SET last_login_at = ? WHERE id = ?";
    static final String DELETE_CLIENT_SESSIONS_SQL = "DELETE FROM public.portal_sessions WHERE client_id = ?";

    private final JdbcTemplate jdbc;

    public JdbcPortalSessionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<Credentials> findByPhone(String phone) {
        return jdbc.query(BY_PHONE_SQL, (rs, i) -> credentials(rs), phone).stream().findFirst();
    }

    @Override
    public Optional<Credentials> findByEmail(String email) {
        return jdbc.query(BY_EMAIL_SQL, (rs, i) -> credentials(rs), email).stream().findFirst();
    }

    @Override
    public boolean emailTakenByOther(String email, String phone) {
        return Boolean.TRUE.equals(jdbc.queryForObject(EMAIL_TAKEN_SQL, Boolean.class, email, phone));
    }

    @Override
    public UUID register(String phone, String name, String email, String passwordHash, Instant now) {
        Timestamp at = Timestamp.from(now);
        return jdbc.queryForObject(REGISTER_SQL, UUID.class, UUID.randomUUID(), phone, name, email, passwordHash, at,
                at);
    }

    @Override
    public void touchLogin(UUID clientId, Instant now) {
        jdbc.update(TOUCH_SQL, Timestamp.from(now), clientId);
    }

    @Override
    public int deleteSessionsOf(UUID clientId) {
        return jdbc.update(DELETE_CLIENT_SESSIONS_SQL, clientId);
    }

    private static Credentials credentials(ResultSet rs) throws SQLException {
        return new Credentials(rs.getObject("id", UUID.class), rs.getString("phone"), rs.getString("password_hash"));
    }

    @Override
    public UUID upsertClient(String phone, Instant now) {
        Timestamp at = Timestamp.from(now);
        return jdbc.queryForObject(UPSERT_CLIENT_SQL, UUID.class, UUID.randomUUID(), phone, at, at);
    }

    @Override
    public void insertSession(byte[] tokenHash, UUID clientId, Instant createdAt, Instant expiresAt) {
        jdbc.update(INSERT_SESSION_SQL, tokenHash, clientId, Timestamp.from(createdAt), Timestamp.from(expiresAt));
    }

    @Override
    public int trimSessions(UUID clientId, int keep) {
        return jdbc.update(TRIM_SQL, clientId, clientId, keep);
    }

    @Override
    public Optional<String> phoneOfSession(byte[] tokenHash, Instant now) {
        List<String> phones = jdbc.queryForList(PHONE_SQL, String.class, tokenHash, Timestamp.from(now));
        return phones.stream().findFirst();
    }

    @Override
    public int deleteSession(byte[] tokenHash) {
        return jdbc.update(DELETE_SQL, (Object) tokenHash);
    }

    @Override
    public int purgeExpiredBefore(Instant cutoff) {
        return jdbc.update(PURGE_SQL, Timestamp.from(cutoff));
    }
}
