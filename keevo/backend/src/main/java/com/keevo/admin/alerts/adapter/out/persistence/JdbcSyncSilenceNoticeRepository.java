package com.keevo.admin.alerts.adapter.out.persistence;

import com.keevo.admin.alerts.domain.port.out.SyncSilenceNoticeRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** JdbcSyncSilenceNoticeRepository — {@code public.sync_silence_notices} (Story 9-5, FR81). */
@Repository
public class JdbcSyncSilenceNoticeRepository implements SyncSilenceNoticeRepository {

    static final String UPSERT_SQL =
            "INSERT INTO public.sync_silence_notices (tenant_id, notified_at, last_push_at_at_notice) "
            + "VALUES (?, ?, ?) ON CONFLICT (tenant_id) DO UPDATE "
            + "SET notified_at = EXCLUDED.notified_at, last_push_at_at_notice = EXCLUDED.last_push_at_at_notice";

    private final JdbcTemplate jdbc;

    public JdbcSyncSilenceNoticeRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<Instant> lastPushAtNotice(UUID tenantId) {
        return jdbc.query("SELECT last_push_at_at_notice FROM public.sync_silence_notices WHERE tenant_id = ?",
                        (rs, i) -> rs.getTimestamp(1).toInstant(), tenantId)
                .stream().findFirst();
    }

    @Override
    public void recordNotice(UUID tenantId, Instant notifiedAt, Instant lastPushAt) {
        jdbc.update(UPSERT_SQL, tenantId, Timestamp.from(notifiedAt), Timestamp.from(lastPushAt));
    }

    @Override
    public void deleteNotice(UUID tenantId) {
        jdbc.update("DELETE FROM public.sync_silence_notices WHERE tenant_id = ?", tenantId);
    }
}
