package com.keevo.identity.auth.adapter.out.persistence.impl;

import com.keevo.identity.auth.domain.port.out.AccountDeletionLogRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Story 16.6 — {@code public.account_deletion_log} over raw JDBC (fully-qualified table, no
 * search_path dependency). The account code is copied from {@code public.tenants} in the same
 * statement: the trace never stores a name, a phone or a user id.
 */
@Component
public class JdbcAccountDeletionLogRepository implements AccountDeletionLogRepository {

    private static final Logger log = LoggerFactory.getLogger(JdbcAccountDeletionLogRepository.class);

    static final String INSERT_SQL =
            "INSERT INTO public.account_deletion_log (tenant_id, tenant_code, event, source, scheduled_for) "
            + "SELECT t.id, t.code, CAST(? AS varchar), CAST(? AS varchar), CAST(? AS timestamptz) "
            + "FROM public.tenants t WHERE t.id = ?";

    /** REQUESTED without an explicit date: the marker the caller just wrote on the tenant row. */
    static final String INSERT_FROM_MARKER_SQL =
            "INSERT INTO public.account_deletion_log (tenant_id, tenant_code, event, source, scheduled_for) "
            + "SELECT t.id, t.code, CAST(? AS varchar), CAST(? AS varchar), t.deletion_scheduled_at "
            + "FROM public.tenants t WHERE t.id = ?";

    private final JdbcTemplate jdbcTemplate;

    public JdbcAccountDeletionLogRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void record(UUID tenantId, Event event, Source source, Instant scheduledFor) {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(event, "event");
        Objects.requireNonNull(source, "source");
        int written = (event == Event.REQUESTED && scheduledFor == null)
                ? jdbcTemplate.update(INSERT_FROM_MARKER_SQL, event.name(), source.name(), tenantId)
                : jdbcTemplate.update(INSERT_SQL, event.name(), source.name(),
                        scheduledFor == null ? null : Timestamp.from(scheduledFor), tenantId);
        if (written == 0) {
            log.warn("ACCOUNT_DELETION_LOG_SKIPPED tenant={} event={} source={} (no tenants row)",
                    tenantId, event, source);
        }
    }
}
