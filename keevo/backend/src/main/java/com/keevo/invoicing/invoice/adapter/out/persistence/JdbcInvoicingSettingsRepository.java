package com.keevo.invoicing.invoice.adapter.out.persistence;

import com.keevo.invoicing.invoice.domain.model.InvoicingSettings;
import com.keevo.invoicing.invoice.domain.port.out.InvoicingSettingsRepository;
import com.keevo.shared.domain.VatRates;
import com.keevo.shared.infrastructure.persistence.TenantContext;
import com.keevo.shared.infrastructure.persistence.TenantSchema;
import com.keevo.store.api.StoreCountry;
import com.keevo.store.api.StoreQueryPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * {@link InvoicingSettingsRepository} on the module-owned single-row table {@code invoicing_settings} (Story 14.1).
 * Story 20.5: the seller's country is the one of the tenant's oldest active store, read through the store module's
 * published port ({@code store :: api}).
 */
@Component
public class JdbcInvoicingSettingsRepository implements InvoicingSettingsRepository {

    private final JdbcTemplate jdbc;
    private final StoreQueryPort stores;

    public JdbcInvoicingSettingsRepository(JdbcTemplate jdbc, StoreQueryPort stores) {
        this.jdbc = jdbc;
        this.stores = stores;
    }

    @Override
    public BigDecimal countryVatRate() {
        String tenant = TenantContext.getCurrentTenant();
        if (!TenantSchema.isValid(tenant)) {
            return null;
        }
        return VatRates.forCountry(stores.oldestActiveStoreCountry(tenant).orElse(StoreCountry.DEFAULT))
                .orElse(null);
    }

    @Override
    public Optional<InvoicingSettings> find() {
        List<InvoicingSettings> rows = jdbc.query("SELECT legal_name, address, phone, niu, rccm, vat_registered, "
                        + "vat_rate, prices_include_vat, payment_details, footer, derive_from_documents FROM "
                        + InvoicingTables.qualified(InvoicingTables.SETTINGS) + " WHERE id = 1",
                (rs, n) -> new InvoicingSettings(rs.getString("legal_name"), rs.getString("address"),
                        rs.getString("phone"), rs.getString("niu"), rs.getString("rccm"),
                        rs.getBoolean("vat_registered"), rs.getBigDecimal("vat_rate"),
                        rs.getBoolean("prices_include_vat"), rs.getString("payment_details"),
                        rs.getString("footer"), (Boolean) rs.getObject("derive_from_documents")));
        return rows.stream().findFirst();
    }

    @Override
    public void save(InvoicingSettings s, UUID updatedBy, Instant now) {
        String table = InvoicingTables.qualified(InvoicingTables.SETTINGS);
        jdbc.update("INSERT INTO " + table + " AS t (id, legal_name, address, phone, niu, rccm, vat_registered, "
                        + "vat_rate, prices_include_vat, payment_details, footer, derive_from_documents, updated_by, updated_at) "
                        + "VALUES (1, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT (id) DO UPDATE SET "
                        + "legal_name = EXCLUDED.legal_name, address = EXCLUDED.address, phone = EXCLUDED.phone, "
                        + "niu = EXCLUDED.niu, rccm = EXCLUDED.rccm, vat_registered = EXCLUDED.vat_registered, "
                        + "vat_rate = EXCLUDED.vat_rate, prices_include_vat = EXCLUDED.prices_include_vat, "
                        + "payment_details = EXCLUDED.payment_details, footer = EXCLUDED.footer, "
                        + "derive_from_documents = EXCLUDED.derive_from_documents, "
                        + "updated_by = EXCLUDED.updated_by, updated_at = EXCLUDED.updated_at",
                s.legalName(), s.address(), s.phone(), s.niu(), s.rccm(), s.vatRegistered(), s.vatRate(),
                s.pricesIncludeVat(), s.paymentDetails(), s.footer(), s.deriveFromDocuments(), updatedBy,
                Timestamp.from(now));
    }
}
