package com.keevo.admin.alerts.adapter.out.persistence;

import com.keevo.admin.alerts.domain.port.out.AlertAckRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.UUID;

/** JdbcAlertAckRepository — {@code public.admin_alert_acks} (Story 9-5). */
@Repository
public class JdbcAlertAckRepository implements AlertAckRepository {

    static final String INSERT_SQL =
            "INSERT INTO public.admin_alert_acks (alert_key, handled_at, handled_by) VALUES (?, ?, ?) "
            + "ON CONFLICT (alert_key) DO NOTHING";

    private final JdbcTemplate jdbc;

    public JdbcAlertAckRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Map<String, Instant> handledAt(Collection<String> keys) {
        Map<String, Instant> out = new HashMap<>();
        if (keys.isEmpty()) return out;
        Object[] args = new LinkedHashSet<>(keys).toArray();
        String in = String.join(",", Collections.nCopies(args.length, "?"));
        jdbc.query("SELECT alert_key, handled_at FROM public.admin_alert_acks WHERE alert_key IN (" + in + ")",
                (RowCallbackHandler) rs -> out.put(rs.getString("alert_key"), rs.getTimestamp("handled_at").toInstant()),
                args);
        return out;
    }

    @Override
    public void markHandled(String key, Instant handledAt, UUID handledBy) {
        jdbc.update(INSERT_SQL, key, Timestamp.from(handledAt), handledBy);
    }

    @Override
    public int delete(Collection<String> keys) {
        if (keys.isEmpty()) return 0;
        Object[] args = new LinkedHashSet<>(keys).toArray();
        String in = String.join(",", Collections.nCopies(args.length, "?"));
        return jdbc.update("DELETE FROM public.admin_alert_acks WHERE alert_key IN (" + in + ")", args);
    }

    @Override
    public int purgeBefore(Instant cutoff) {
        return jdbc.update("DELETE FROM public.admin_alert_acks WHERE handled_at < ?", Timestamp.from(cutoff));
    }
}
