package com.keevo.invoicing.invoice.adapter.out.persistence;

import com.keevo.invoicing.invoice.domain.port.out.SaleInvoiceStateRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

/** {@link SaleInvoiceStateRepository} on {@code sale_invoice_states} of the tenant schema (Story 14.7), plain JDBC. */
@Component
public class JdbcSaleInvoiceStateRepository implements SaleInvoiceStateRepository {

    private final JdbcTemplate jdbc;

    public JdbcSaleInvoiceStateRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public State lock(UUID saleId) {
        String table = InvoicingTables.qualified(InvoicingTables.SALE_STATES);
        jdbc.update("INSERT INTO " + table + " (sale_id) VALUES (?) ON CONFLICT (sale_id) DO NOTHING", saleId);
        return jdbc.queryForObject("SELECT cancelled_at, last_correction_at FROM " + table
                + " WHERE sale_id = ? FOR UPDATE", (rs, n) -> new State(instant(rs.getTimestamp("cancelled_at")),
                instant(rs.getTimestamp("last_correction_at"))), saleId);
    }

    @Override
    public void markCancelled(UUID saleId, Instant at) {
        jdbc.update("UPDATE " + InvoicingTables.qualified(InvoicingTables.SALE_STATES)
                + " SET cancelled_at = COALESCE(cancelled_at, ?) WHERE sale_id = ?", Timestamp.from(at), saleId);
    }

    @Override
    public void markCorrected(UUID saleId, Instant at) {
        jdbc.update("UPDATE " + InvoicingTables.qualified(InvoicingTables.SALE_STATES)
                + " SET last_correction_at = ? WHERE sale_id = ?", Timestamp.from(at), saleId);
    }

    private static Instant instant(Timestamp t) {
        return t == null ? null : t.toInstant();
    }
}
