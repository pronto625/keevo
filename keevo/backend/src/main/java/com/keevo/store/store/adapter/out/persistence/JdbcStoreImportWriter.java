package com.keevo.store.store.adapter.out.persistence;

import com.keevo.shared.infrastructure.persistence.TenantSchema;
import com.keevo.store.api.StoreImportPort.ImportedStore;
import com.keevo.store.api.StoreType;
import com.keevo.store.store.domain.port.out.StoreImportWriter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.PreparedStatement;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

/**
 * Story 24.2 — {@link StoreImportWriter} as one set-based statement on the explicit, validated schema:
 * {@code INSERT … SELECT FROM unnest(…) ON CONFLICT (id) DO NOTHING RETURNING id} (one round trip, the ids inserted
 * come back). Dates travel as ISO-8601 text cast to {@code timestamptz}; client sharing stays off (ADR-08).
 */
@Component
class JdbcStoreImportWriter implements StoreImportWriter {

    private final JdbcTemplate jdbc;

    JdbcStoreImportWriter(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Set<UUID> insertAbsent(String tenantSchema, List<ImportedStore> stores) {
        if (stores.isEmpty()) {
            return Set.of();
        }
        String table = "\"" + TenantSchema.validate(tenantSchema) + "\".stores";
        String sql = "INSERT INTO " + table + " (id, name, is_active, created_at, updated_at, address, phone, type, "
                + "category, description, niu, rccm, country, share_clients) "
                + "SELECT id, name, is_active, created_at::timestamptz, updated_at::timestamptz, address, phone, ?, "
                + "category, description, niu, rccm, country, false "
                + "FROM unnest(?::uuid[], ?::varchar[], ?::boolean[], ?::text[], ?::text[], ?::varchar[], "
                + "?::varchar[], ?::varchar[], ?::varchar[], ?::varchar[], ?::varchar[], ?::varchar[]) "
                + "AS x(id, name, is_active, created_at, updated_at, address, phone, category, description, niu, "
                + "rccm, country) ON CONFLICT (id) DO NOTHING RETURNING id";
        Set<UUID> inserted = new LinkedHashSet<>();
        jdbc.query(con -> {
            PreparedStatement ps = con.prepareStatement(sql);
            ps.setString(1, StoreType.STORE.name());
            ps.setArray(2, con.createArrayOf("uuid", stores.stream().map(ImportedStore::id).toArray()));
            ps.setArray(3, con.createArrayOf("varchar", column(stores, ImportedStore::name)));
            ps.setArray(4, con.createArrayOf("boolean", stores.stream().map(ImportedStore::active).toArray()));
            ps.setArray(5, con.createArrayOf("text", column(stores, s -> s.createdAt().toString())));
            ps.setArray(6, con.createArrayOf("text", column(stores, s -> s.updatedAt().toString())));
            ps.setArray(7, con.createArrayOf("varchar", column(stores, ImportedStore::address)));
            ps.setArray(8, con.createArrayOf("varchar", column(stores, ImportedStore::phone)));
            ps.setArray(9, con.createArrayOf("varchar", column(stores, ImportedStore::category)));
            ps.setArray(10, con.createArrayOf("varchar", column(stores, ImportedStore::description)));
            ps.setArray(11, con.createArrayOf("varchar", column(stores, ImportedStore::niu)));
            ps.setArray(12, con.createArrayOf("varchar", column(stores, ImportedStore::rccm)));
            ps.setArray(13, con.createArrayOf("varchar", column(stores, ImportedStore::country)));
            return ps;
        }, rs -> {
            inserted.add(rs.getObject("id", UUID.class));
        });
        return inserted;
    }

    private static Object[] column(List<ImportedStore> stores, Function<ImportedStore, String> field) {
        return stores.stream().map(field).toArray();
    }
}
