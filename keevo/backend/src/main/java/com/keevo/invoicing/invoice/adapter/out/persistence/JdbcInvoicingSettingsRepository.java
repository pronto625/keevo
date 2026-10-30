package com.keevo.invoicing.invoice.adapter.out.persistence;

import com.keevo.invoicing.invoice.domain.model.InvoicingSettings;
import com.keevo.invoicing.invoice.domain.port.out.InvoicingSettingsRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** {@link InvoicingSettingsRepository} on the module-owned single-row table {@code invoicing_settings} (Story 14.1). */
@Component
public class JdbcInvoicingSettingsRepository implements InvoicingSettingsRepository {

    private final JdbcTemplate jdbc;

    public JdbcInvoicingSettingsRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<InvoicingSettings> find() {
        List<InvoicingSettings> rows = jdbc.query("SELECT legal_name, address, phone, niu, rccm, vat_registered, "
                        + "vat_rate, prices_include_vat, payment_details, footer FROM "
                        + InvoicingTables.qualified(InvoicingTables.SETTINGS) + " WHERE id = 1",
                (rs, n) -> new InvoicingSettings(rs.getString("legal_name"), rs.getString("address"),
                        rs.getString("phone"), rs.getString("niu"), rs.getString("rccm"),
                        rs.getBoolean("vat_registered"), rs.getBigDecimal("vat_rate"),
                        rs.getBoolean("prices_include_vat"), rs.getString("payment_details"),
                        rs.getString("footer")));
        return rows.stream().findFirst();
    }

    @Override
    public void save(InvoicingSettings s, UUID updatedBy, Instant now) {
        String table = InvoicingTables.qualified(InvoicingTables.SETTINGS);
        jdbc.update("INSERT INTO " + table + " AS t (id, legal_name, address, phone, niu, rccm, vat_registered, "
                        + "vat_rate, prices_include_vat, payment_details, footer, updated_by, updated_at) "
                        + "VALUES (1, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT (id) DO UPDATE SET "
                        + "legal_name = EXCLUDED.legal_name, address = EXCLUDED.address, phone = EXCLUDED.phone, "
                        + "niu = EXCLUDED.niu, rccm = EXCLUDED.rccm, vat_registered = EXCLUDED.vat_registered, "
                        + "vat_rate = EXCLUDED.vat_rate, prices_include_vat = EXCLUDED.prices_include_vat, "
                        + "payment_details = EXCLUDED.payment_details, footer = EXCLUDED.footer, "
                        + "updated_by = EXCLUDED.updated_by, updated_at = EXCLUDED.updated_at",
                s.legalName(), s.address(), s.phone(), s.niu(), s.rccm(), s.vatRegistered(), s.vatRate(),
                s.pricesIncludeVat(), s.paymentDetails(), s.footer(), updatedBy, Timestamp.from(now));
    }
}
