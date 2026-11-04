package com.keevo.messaging.notification.adapter.out.persistence.impl;

import com.keevo.messaging.notification.domain.model.ExpoPushTicket;
import com.keevo.messaging.notification.domain.port.out.ExpoPushTicketRepository;
import com.keevo.shared.infrastructure.persistence.TenantSchema;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * Story 19.9b — {@code public.expo_push_tickets} over raw JDBC (fully-qualified table, no tenant schema in the SQL:
 * the schema is a column value, checked with {@link TenantSchema#isValid} before it is stored). One statement per
 * method; the batch read is keyset-paginated on {@code (created_at, ticket_id)} over the matching composite index
 * {@code ix_expo_push_tickets_created_id} (row comparison seek, no rescan of a send's equal {@code created_at}).
 */
@Component
public class JdbcExpoPushTicketRepository implements ExpoPushTicketRepository {

    static final String INSERT_SQL = "INSERT INTO public.expo_push_tickets (ticket_id, token, tenant_schema, created_at) "
            + "VALUES (?, ?, ?, ?) ON CONFLICT (ticket_id) DO NOTHING";
    static final String FIND_SQL = "SELECT ticket_id, token, tenant_schema, created_at FROM public.expo_push_tickets "
            + "WHERE created_at < ? ORDER BY created_at, ticket_id LIMIT ?";
    static final String FIND_AFTER_SQL = "SELECT ticket_id, token, tenant_schema, created_at "
            + "FROM public.expo_push_tickets WHERE created_at < ? AND (created_at, ticket_id) > (?, ?) "
            + "ORDER BY created_at, ticket_id LIMIT ?";
    static final String DELETE_SQL = "DELETE FROM public.expo_push_tickets WHERE ticket_id = ANY(?)";
    static final String PURGE_SQL = "DELETE FROM public.expo_push_tickets WHERE created_at < ?";

    private final JdbcTemplate jdbc;

    public JdbcExpoPushTicketRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void saveAll(String tenantSchema, Map<String, String> tokenByTicketId) {
        if (tokenByTicketId == null || tokenByTicketId.isEmpty() || !TenantSchema.isValid(tenantSchema)) return;
        Timestamp now = Timestamp.from(Instant.now());
        List<Object[]> rows = new ArrayList<>(tokenByTicketId.size());
        tokenByTicketId.forEach((id, token) -> rows.add(new Object[]{id, token, tenantSchema, now}));
        jdbc.batchUpdate(INSERT_SQL, rows);
    }

    @Override
    public List<ExpoPushTicket> findCreatedBefore(Instant before, Instant afterCreatedAt, String afterTicketId,
                                                  int limit) {
        if (afterCreatedAt == null || afterTicketId == null) {
            return jdbc.query(FIND_SQL, (rs, n) -> map(rs), Timestamp.from(before), limit);
        }
        return jdbc.query(FIND_AFTER_SQL, (rs, n) -> map(rs), Timestamp.from(before), Timestamp.from(afterCreatedAt),
                afterTicketId, limit);
    }

    private static ExpoPushTicket map(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new ExpoPushTicket(rs.getString("ticket_id"), rs.getString("token"), rs.getString("tenant_schema"),
                rs.getTimestamp("created_at").toInstant());
    }

    @Override
    public int deleteByIds(Collection<String> ticketIds) {
        if (ticketIds == null || ticketIds.isEmpty()) return 0;
        return jdbc.update(con -> {
            var ps = con.prepareStatement(DELETE_SQL);
            ps.setArray(1, con.createArrayOf("varchar", ticketIds.toArray()));
            return ps;
        });
    }

    @Override
    public int purgeCreatedBefore(Instant before) {
        return jdbc.update(PURGE_SQL, Timestamp.from(before));
    }
}
