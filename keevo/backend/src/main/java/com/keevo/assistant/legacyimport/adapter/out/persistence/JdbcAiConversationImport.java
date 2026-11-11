package com.keevo.assistant.legacyimport.adapter.out.persistence;

import com.keevo.assistant.api.AiConversationImportPort;
import com.keevo.shared.infrastructure.persistence.TenantContext;
import com.keevo.shared.infrastructure.persistence.TenantSchema;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.PreparedStatement;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

/**
 * Story 24.6 — {@link AiConversationImportPort} as one {@code INSERT … SELECT FROM unnest(…) ON CONFLICT (id) DO NOTHING
 * RETURNING id} on the bound tenant's {@code ai_conversations} (module script V1: title ≤ 120, message count
 * 0..10000, JSON arrays). The 21.4 per-user cap is respected by the caller ({@link #MAX_PER_USER} per user per call).
 */
@Component
class JdbcAiConversationImport implements AiConversationImportPort {

    private static final int MAX_TITLE = 120;

    private final JdbcTemplate jdbc;

    JdbcAiConversationImport(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Set<UUID> importConversations(String tenantSchema, List<ImportedConversation> conversations) {
        TenantContext.requireCurrent(tenantSchema);
        String schema = TenantSchema.validate(tenantSchema);
        if (conversations.isEmpty()) {
            return Set.of();
        }
        Set<UUID> ids = new HashSet<>();
        Map<UUID, Integer> perUser = new HashMap<>();
        for (ImportedConversation c : conversations) {
            require(ids.add(c.id()), "duplicate conversation");
            require(c.userId() != null && c.createdAt() != null && c.updatedAt() != null, "conversation owner");
            require(perUser.merge(c.userId(), 1, Integer::sum) <= MAX_PER_USER, "too many conversations");
            require(c.title() != null && c.title().codePointCount(0, c.title().length()) <= MAX_TITLE,
                    "conversation title");
            require(c.messageCount() >= 0 && c.messageCount() <= 10_000, "conversation count");
            require(array(c.messages()) && array(c.aiHistory()), "conversation JSON");
        }
        String sql = "INSERT INTO \"" + schema + "\".ai_conversations (id, user_id, title, message_count, messages, "
                + "ai_history, created_at, updated_at) SELECT x.id, x.user_id, x.title, x.n, x.messages::jsonb, "
                + "x.history::jsonb, x.created_at::timestamptz, x.updated_at::timestamptz "
                + "FROM unnest(?::uuid[], ?::uuid[], ?::varchar[], ?::int4[], ?::text[], ?::text[], ?::text[], "
                + "?::text[]) AS x(id, user_id, title, n, messages, history, created_at, updated_at) "
                + "WHERE jsonb_typeof(x.messages::jsonb) = 'array' AND jsonb_typeof(x.history::jsonb) = 'array' "
                + "ON CONFLICT (id) DO NOTHING RETURNING id";
        Set<UUID> inserted = new LinkedHashSet<>();
        jdbc.query(con -> {
            PreparedStatement ps = con.prepareStatement(sql);
            ps.setArray(1, con.createArrayOf("uuid", column(conversations, ImportedConversation::id)));
            ps.setArray(2, con.createArrayOf("uuid", column(conversations, ImportedConversation::userId)));
            ps.setArray(3, con.createArrayOf("varchar", column(conversations, ImportedConversation::title)));
            ps.setArray(4, con.createArrayOf("int4", column(conversations, ImportedConversation::messageCount)));
            ps.setArray(5, con.createArrayOf("text", column(conversations, ImportedConversation::messages)));
            ps.setArray(6, con.createArrayOf("text", column(conversations, ImportedConversation::aiHistory)));
            ps.setArray(7, con.createArrayOf("text", column(conversations, c -> text(c.createdAt()))));
            ps.setArray(8, con.createArrayOf("text", column(conversations, c -> text(c.updatedAt()))));
            return ps;
        }, rs -> {
            inserted.add(rs.getObject("id", UUID.class));
        });
        return inserted;
    }

    /** A JSON array text (checked again in SQL: {@code jsonb_typeof}). */
    private static boolean array(String json) {
        return json != null && json.strip().startsWith("[");
    }

    private static String text(Instant at) {
        return at.toString();
    }

    private static Object[] column(List<ImportedConversation> rows, Function<ImportedConversation, Object> field) {
        return rows.stream().map(field).toArray();
    }

    private static void require(boolean ok, String what) {
        if (!ok) {
            throw new IllegalArgumentException("invalid imported AI conversation: " + what);
        }
    }
}
