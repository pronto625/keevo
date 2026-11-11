package com.keevo.crm.contact.adapter.out.persistence;

import com.keevo.crm.api.ClientImportPort.ImportedClient;
import com.keevo.crm.contact.domain.port.out.ClientImportWriter;
import com.keevo.shared.infrastructure.persistence.TenantSchema;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.PreparedStatement;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

/**
 * Story 24.3 — {@link ClientImportWriter} as one statement on the explicit, validated schema:
 * {@code INSERT … SELECT FROM unnest(…) ON CONFLICT (id) DO NOTHING RETURNING id} (dates as ISO-8601 text cast to
 * {@code timestamptz} / {@code date}). {@code phone_normalized} = the E.164 phone (same-store duplicate warning,
 * STOP / START of 21.6).
 */
@Component
class JdbcClientImportWriter implements ClientImportWriter {

    private final JdbcTemplate jdbc;

    JdbcClientImportWriter(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Set<UUID> insertAbsent(String tenantSchema, List<ImportedClient> clients) {
        if (clients.isEmpty()) {
            return Set.of();
        }
        String table = "\"" + TenantSchema.validate(tenantSchema) + "\".clients";
        String sql = "INSERT INTO " + table + " (id, name, phone, phone_normalized, email, notes, archived, store_id, "
                + "client_type, niu, rccm, address, contact_name, website, birth_date, marketing_consent_at, "
                + "marketing_consent_by, marketing_opt_out_at, created_at, updated_at) "
                + "SELECT id, name, phone, phone, email, notes, false, store_id, client_type, niu, rccm, address, "
                + "contact_name, website, birth_date::date, consent_at::timestamptz, consent_by, "
                + "opt_out_at::timestamptz, created_at::timestamptz, updated_at::timestamptz "
                + "FROM unnest(?::uuid[], ?::varchar[], ?::varchar[], ?::varchar[], ?::text[], ?::uuid[], ?::varchar[], "
                + "?::varchar[], ?::varchar[], ?::varchar[], ?::varchar[], ?::varchar[], ?::text[], ?::text[], "
                + "?::uuid[], ?::text[], ?::text[], ?::text[]) AS x(id, name, phone, email, notes, store_id, "
                + "client_type, niu, rccm, address, contact_name, website, birth_date, consent_at, consent_by, "
                + "opt_out_at, created_at, updated_at) ON CONFLICT (id) DO NOTHING RETURNING id";
        Set<UUID> inserted = new LinkedHashSet<>();
        jdbc.query(con -> {
            PreparedStatement ps = con.prepareStatement(sql);
            ps.setArray(1, con.createArrayOf("uuid", column(clients, ImportedClient::id)));
            ps.setArray(2, con.createArrayOf("varchar", column(clients, ImportedClient::name)));
            ps.setArray(3, con.createArrayOf("varchar", column(clients, ImportedClient::phone)));
            ps.setArray(4, con.createArrayOf("varchar", column(clients, ImportedClient::email)));
            ps.setArray(5, con.createArrayOf("text", column(clients, ImportedClient::notes)));
            ps.setArray(6, con.createArrayOf("uuid", column(clients, ImportedClient::storeId)));
            ps.setArray(7, con.createArrayOf("varchar", column(clients, ImportedClient::clientType)));
            ps.setArray(8, con.createArrayOf("varchar", column(clients, ImportedClient::niu)));
            ps.setArray(9, con.createArrayOf("varchar", column(clients, ImportedClient::rccm)));
            ps.setArray(10, con.createArrayOf("varchar", column(clients, ImportedClient::address)));
            ps.setArray(11, con.createArrayOf("varchar", column(clients, ImportedClient::contactName)));
            ps.setArray(12, con.createArrayOf("varchar", column(clients, ImportedClient::website)));
            ps.setArray(13, con.createArrayOf("text", column(clients, c -> text(c.birthDate()))));
            ps.setArray(14, con.createArrayOf("text", column(clients, c -> text(c.marketingConsentAt()))));
            ps.setArray(15, con.createArrayOf("uuid", column(clients, ImportedClient::marketingConsentBy)));
            ps.setArray(16, con.createArrayOf("text", column(clients, c -> text(c.marketingOptOutAt()))));
            ps.setArray(17, con.createArrayOf("text", column(clients, c -> text(c.createdAt()))));
            ps.setArray(18, con.createArrayOf("text", column(clients, c -> text(c.updatedAt()))));
            return ps;
        }, rs -> {
            inserted.add(rs.getObject("id", UUID.class));
        });
        return inserted;
    }

    private static String text(Instant at) {
        return at == null ? null : at.toString();
    }

    private static String text(LocalDate date) {
        return date == null ? null : date.toString();
    }

    private static Object[] column(List<ImportedClient> clients, Function<ImportedClient, Object> field) {
        return clients.stream().map(field).toArray();
    }
}
