package com.keevo.shared.ai.internal;

import com.keevo.shared.infrastructure.persistence.TenantSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;

/**
 * Story 21.8 — {@link AiToolCallAudit} on {@code public.ai_tool_calls} (V64): one INSERT per call, the company resolved
 * from its schema name in the same statement (unique index on {@code tenants.schema_name}); an invalid or unknown
 * schema inserts nothing. Own transaction ({@code REQUIRES_NEW}): the row survives a caller's rollback (audit-on-attempt, like
 * {@code AuditPort}).
 */
@Component
public class JdbcAiToolCallAudit implements AiToolCallAudit {

    static final int MAX_CODE = 60;
    static final int MAX_TOOL = 64;
    static final int PURGE_BATCH = 10_000;

    private static final Logger log = LoggerFactory.getLogger(JdbcAiToolCallAudit.class);

    private final JdbcTemplate jdbc;

    public JdbcAiToolCallAudit(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(AiToolCall call) {
        if (!TenantSchema.isValid(call.tenantSchema())) {
            return; // not a company schema (platform session): nothing to attach the call to
        }
        int rows = jdbc.update("INSERT INTO public.ai_tool_calls (tenant_id, actor_id, tool, channel, outcome, "
                        + "error_code, duration_ms, called_at, access_token_id) SELECT t.id, ?, ?, ?, ?, ?, ?, ?, ? "
                        + "FROM public.tenants t WHERE t.schema_name = ?",
                call.actorId(), truncate(call.tool(), MAX_TOOL), call.channel().name(), call.outcome().name(),
                truncate(call.errorCode(), MAX_CODE), (int) Math.min(Integer.MAX_VALUE, Math.max(0, call.durationMs())),
                Timestamp.from(call.calledAt()), call.accessTokenId(), call.tenantSchema());
        if (rows == 0) {
            // a company schema without its tenants row: the call ran but cannot be attached — say so
            log.warn("AI tool call not audited: no company row for the session's schema (tool {})", call.tool());
        }
    }

    /**
     * Deletes in batches of {@value #PURGE_BATCH} rows (each its own short statement on the {@code called_at} index), so
     * a backlog never becomes one long DELETE holding its locks.
     */
    @Override
    public int purgeBefore(java.time.Instant cutoff) {
        int total = 0;
        int deleted;
        do {
            deleted = jdbc.update("DELETE FROM public.ai_tool_calls WHERE id IN (SELECT id FROM public.ai_tool_calls "
                    + "WHERE called_at < ? ORDER BY called_at LIMIT " + PURGE_BATCH + ")", Timestamp.from(cutoff));
            total += deleted;
        } while (deleted == PURGE_BATCH);
        return total;
    }

    private static String truncate(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max);
    }
}
