package com.keevo.shared.ai.internal;

import com.keevo.shared.infrastructure.persistence.TenantSchema;
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
        jdbc.update("INSERT INTO public.ai_tool_calls (tenant_id, actor_id, tool, channel, outcome, error_code, "
                        + "duration_ms, called_at) SELECT t.id, ?, ?, ?, ?, ?, ?, ? FROM public.tenants t "
                        + "WHERE t.schema_name = ?",
                call.actorId(), truncate(call.tool(), MAX_TOOL), call.channel().name(), call.outcome().name(),
                truncate(call.errorCode(), MAX_CODE), (int) Math.min(Integer.MAX_VALUE, Math.max(0, call.durationMs())),
                Timestamp.from(call.calledAt()), call.tenantSchema());
    }

    @Override
    @Transactional
    public int purgeBefore(java.time.Instant cutoff) {
        return jdbc.update("DELETE FROM public.ai_tool_calls WHERE called_at < ?", Timestamp.from(cutoff));
    }

    private static String truncate(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max);
    }
}
