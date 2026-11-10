package com.keevo.assistant.conversation.adapter.out.persistence;

import com.keevo.assistant.conversation.domain.model.AiConversation;
import com.keevo.assistant.conversation.domain.model.AiConversationSummary;
import com.keevo.assistant.conversation.domain.port.out.AiConversationRepository;
import com.keevo.shared.infrastructure.persistence.TenantContext;
import com.keevo.shared.infrastructure.persistence.TenantSchema;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * {@link AiConversationRepository} on the module-owned table {@code ai_conversations} of the tenant schema (Story
 * 21.4) — plain JDBC, every statement names {@code "kv_xxxxxx".ai_conversations} (schema from {@link TenantContext},
 * validated) and filters on {@code user_id}. The list reads no JSON column and uses
 * {@code idx_ai_conversations_user_updated} ({@code LIMIT}); single rows go by primary key.
 */
@Component
public class JdbcAiConversationRepository implements AiConversationRepository {

    static final String TABLE = "ai_conversations";

    private final JdbcTemplate jdbc;

    public JdbcAiConversationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** {@code "kv_xxxxxx".ai_conversations} for the bound tenant; no / invalid tenant → {@link IllegalStateException}. */
    static String table() {
        String schema = TenantContext.getCurrentTenant();
        try {
            TenantSchema.validate(schema);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("AI conversations used without a valid tenant schema bound");
        }
        return "\"" + schema + "\"." + TABLE;
    }

    @Override
    public List<AiConversationSummary> list(UUID userId, int limit) {
        return jdbc.query("SELECT id, title, message_count, created_at, updated_at FROM " + table()
                        + " WHERE user_id = ? ORDER BY updated_at DESC, id LIMIT ?",
                (rs, n) -> new AiConversationSummary(rs.getObject("id", UUID.class), rs.getString("title"),
                        rs.getInt("message_count"), rs.getTimestamp("created_at").toInstant(),
                        rs.getTimestamp("updated_at").toInstant()),
                userId, limit);
    }

    @Override
    public long count(UUID userId) {
        Long n = jdbc.queryForObject("SELECT count(*) FROM " + table() + " WHERE user_id = ?", Long.class, userId);
        return n == null ? 0 : n;
    }

    @Override
    public Optional<AiConversation> find(UUID userId, UUID id) {
        List<AiConversation> rows = jdbc.query("SELECT id, user_id, title, message_count, messages::text AS messages, "
                        + "ai_history::text AS ai_history, created_at, updated_at FROM " + table()
                        + " WHERE id = ? AND user_id = ?",
                (rs, n) -> new AiConversation(rs.getObject("id", UUID.class), rs.getObject("user_id", UUID.class),
                        rs.getString("title"), rs.getInt("message_count"), rs.getString("messages"),
                        rs.getString("ai_history"), rs.getTimestamp("created_at").toInstant(),
                        rs.getTimestamp("updated_at").toInstant()),
                id, userId);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    @Override
    public void insert(AiConversation c) {
        jdbc.update("INSERT INTO " + table() + " (id, user_id, title, message_count, messages, ai_history, created_at, "
                        + "updated_at) VALUES (?, ?, ?, ?, ?::jsonb, ?::jsonb, ?, ?)",
                c.id(), c.userId(), c.title(), c.messageCount(), c.messages(), c.aiHistory(),
                Timestamp.from(c.createdAt()), Timestamp.from(c.updatedAt()));
    }

    /** One transaction: a per-user advisory lock, the indexed count, the insert. */
    @Override
    @org.springframework.transaction.annotation.Transactional
    public boolean insertUnderCap(AiConversation c, int cap) {
        jdbc.queryForList("SELECT pg_advisory_xact_lock(hashtext(?))", "ai_conversations:" + c.userId());
        if (count(c.userId()) >= cap) {
            return false;
        }
        insert(c);
        return true;
    }

    @Override
    public boolean update(UUID userId, UUID id, String messages, String aiHistory, Integer messageCount,
                          String title) {
        return jdbc.update("UPDATE " + table() + " SET messages = COALESCE(?::jsonb, messages), "
                        + "ai_history = COALESCE(?::jsonb, ai_history), message_count = COALESCE(?, message_count), "
                        + "title = COALESCE(?, title), updated_at = now() WHERE id = ? AND user_id = ?",
                messages, aiHistory, messageCount, title, id, userId) > 0;
    }

    @Override
    public void delete(UUID userId, UUID id) {
        jdbc.update("DELETE FROM " + table() + " WHERE id = ? AND user_id = ?", id, userId);
    }

    @Override
    public int deleteAll(UUID userId, Collection<UUID> ids) {
        if (ids.isEmpty()) {
            return 0;
        }
        return jdbc.update(con -> {
            var ps = con.prepareStatement("DELETE FROM " + table() + " WHERE user_id = ? AND id = ANY(?)");
            ps.setObject(1, userId);
            ps.setArray(2, con.createArrayOf("uuid", ids.toArray()));
            return ps;
        });
    }
}
