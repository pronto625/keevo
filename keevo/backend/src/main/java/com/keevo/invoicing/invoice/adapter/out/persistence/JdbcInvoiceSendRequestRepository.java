package com.keevo.invoicing.invoice.adapter.out.persistence;

import com.keevo.invoicing.invoice.domain.model.InvoiceSendRequest;
import com.keevo.invoicing.invoice.domain.model.SendChannel;
import com.keevo.invoicing.invoice.domain.model.SendStatus;
import com.keevo.invoicing.invoice.domain.port.out.InvoiceSendRequestRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Story 20.8 — {@link InvoiceSendRequestRepository} on {@code invoice_send_requests} (invoicing V18), plain JDBC in the
 * caller's transaction. Claims are single conditional UPDATEs (the status transition PENDING → SENDING is the lock);
 * the batch claim uses {@code FOR UPDATE SKIP LOCKED} on the partial index of due rows.
 */
@Component
public class JdbcInvoiceSendRequestRepository implements InvoiceSendRequestRepository {

    private static final String COLUMNS = "id, invoice_id, operation_id, channel, recipient_override, status, "
            + "attempts, last_error_code, next_attempt_at, created_by, created_at, updated_at";

    private final JdbcTemplate jdbc;

    public JdbcInvoiceSendRequestRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static String table() {
        return InvoicingTables.qualified(InvoicingTables.SEND_REQUESTS);
    }

    @Override
    public boolean insertIfAbsent(InvoiceSendRequest r) {
        return jdbc.update("INSERT INTO " + table() + " (" + COLUMNS + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) "
                + "ON CONFLICT (operation_id, channel) DO NOTHING", values(r)) == 1;
    }

    @Override
    public void insert(InvoiceSendRequest r) {
        jdbc.update("INSERT INTO " + table() + " (" + COLUMNS + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                values(r));
    }

    private static Object[] values(InvoiceSendRequest r) {
        return new Object[]{r.id(), r.invoiceId(), r.operationId(), r.channel().name(), r.recipientOverride(),
                r.status().name(), r.attempts(), r.lastErrorCode(), ts(r.nextAttemptAt()), r.createdBy(),
                ts(r.createdAt()), ts(r.updatedAt())};
    }

    @Override
    public Optional<InvoiceSendRequest> claim(UUID id, Instant now) {
        return jdbc.query("UPDATE " + table() + " SET status = 'SENDING', attempts = attempts + 1, updated_at = ? "
                        + "WHERE id = ? AND status = 'PENDING' AND next_attempt_at <= ? RETURNING " + COLUMNS,
                (rs, n) -> row(rs), ts(now), id, ts(now)).stream().findFirst();
    }

    @Override
    public List<InvoiceSendRequest> claimDue(Instant now, int limit) {
        String t = table();
        return jdbc.query("UPDATE " + t + " r SET status = 'SENDING', attempts = r.attempts + 1, updated_at = ? "
                        + "FROM (SELECT id FROM " + t + " WHERE status = 'PENDING' AND next_attempt_at <= ? "
                        + "ORDER BY next_attempt_at LIMIT ? FOR UPDATE SKIP LOCKED) due WHERE r.id = due.id "
                        + "RETURNING " + prefixed("r"),
                (rs, n) -> row(rs), ts(now), ts(now), limit);
    }

    @Override
    public void complete(UUID id, SendStatus status, String errorCode, Instant nextAttemptAt, Instant now) {
        jdbc.update("UPDATE " + table() + " SET status = ?, last_error_code = ?, next_attempt_at = ?, updated_at = ? "
                + "WHERE id = ?", status.name(), errorCode, ts(nextAttemptAt), ts(now), id);
    }

    @Override
    public List<InvoiceSendRequest> expireStale(Instant leaseStart, Instant now) {
        return jdbc.query("UPDATE " + table() + " SET status = 'UNKNOWN', last_error_code = 'INVOICE_SEND_UNCERTAIN', "
                        + "next_attempt_at = NULL, updated_at = ? WHERE status = 'SENDING' AND updated_at < ? "
                        + "RETURNING " + COLUMNS,
                (rs, n) -> row(rs), ts(now), ts(leaseStart));
    }

    @Override
    public List<InvoiceSendRequest> findByOperation(String operationId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM " + table() + " WHERE operation_id = ? ORDER BY channel",
                (rs, n) -> row(rs), operationId);
    }

    @Override
    public Set<String> schemasWithOutbox() {
        return new HashSet<>(jdbc.queryForList("SELECT schemaname FROM pg_catalog.pg_tables "
                + "WHERE tablename = 'invoice_send_requests' AND schemaname ~ '^kv_'", String.class));
    }

    private static String prefixed(String alias) {
        return alias + "." + COLUMNS.replace(", ", ", " + alias + ".");
    }

    private static InvoiceSendRequest row(ResultSet rs) throws SQLException {
        return new InvoiceSendRequest(rs.getObject("id", UUID.class), rs.getObject("invoice_id", UUID.class),
                rs.getString("operation_id"), SendChannel.valueOf(rs.getString("channel")),
                rs.getString("recipient_override"), SendStatus.valueOf(rs.getString("status")),
                rs.getInt("attempts"), rs.getString("last_error_code"), instant(rs, "next_attempt_at"),
                rs.getObject("created_by", UUID.class), instant(rs, "created_at"), instant(rs, "updated_at"));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp t = rs.getTimestamp(column);
        return t == null ? null : t.toInstant();
    }

    private static Timestamp ts(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }
}
