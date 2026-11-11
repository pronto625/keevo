package com.keevo.admin.campaign.adapter.out.persistence;

import com.keevo.admin.campaign.domain.model.AudienceMember;
import com.keevo.admin.campaign.domain.model.Segment;
import com.keevo.admin.campaign.domain.port.out.PlatformAudiencePort;
import com.keevo.admin.crosstenant.CrossTenantSql;
import com.keevo.admin.crosstenant.TenantBranch;
import com.keevo.admin.crosstenant.TenantTable;
import com.keevo.shared.infrastructure.persistence.TenantSchema;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.UnaryOperator;

/**
 * Story 21.6 — {@link PlatformAudiencePort}: (1) when the segment names countries / activities, the companies having
 * an active store matching them, through {@link CrossTenantSql#query} (one {@code LIMIT 1} branch per company, chunks
 * of 100 {@code UNION ALL} branches); (2) one public query: active OWNER memberships ({@code ix_utm_tenant_role}) of
 * live companies ({@code ix_tenants_plan_type} for plans), one row per person (his oldest company), LIMIT.
 */
@Component
public class JdbcPlatformAudienceAdapter implements PlatformAudiencePort {

    private final JdbcTemplate jdbc;
    private final CrossTenantSql crossTenant;

    public JdbcPlatformAudienceAdapter(JdbcTemplate jdbc, CrossTenantSql crossTenant) {
        this.jdbc = jdbc;
        this.crossTenant = crossTenant;
    }

    @Override
    public List<AudienceMember> audience(Segment segment, int limit) {
        List<Object> params = new ArrayList<>();
        String inner = innerSql(segment, params);
        if (inner == null) {
            return List.of();
        }
        // one row per person: his oldest company; then a stable order for the LIMIT
        String query = "SELECT * FROM (" + inner + ") a ORDER BY user_id LIMIT ?";
        params.add(limit);
        return jdbc.query(query, (rs, n) -> new AudienceMember(rs.getObject("user_id", UUID.class),
                rs.getObject("tenant_id", UUID.class), rs.getString("schema_name"), rs.getString("first_name"),
                rs.getString("last_name"), rs.getString("company"), rs.getString("phone_number"),
                rs.getString("email")), params.toArray()).stream()
                .filter(a -> TenantSchema.isValid(a.tenantSchema())).toList();
    }

    @Override
    public Counts count(Segment segment, int cap) {
        List<Object> params = new ArrayList<>();
        String inner = innerSql(segment, params);
        if (inner == null) {
            return new Counts(0, 0, 0);
        }
        params.add(cap);
        // the same audience, counted in SQL (rows never transferred): owners, with a usable phone, with an email
        return jdbc.queryForObject("SELECT count(*)::int, count(*) FILTER (WHERE length(regexp_replace("
                        + "COALESCE(phone_number, ''), '\\D', '', 'g')) BETWEEN 8 AND 15)::int, count(*) FILTER (WHERE "
                        + "email LIKE '%@%')::int FROM (SELECT * FROM (" + inner + ") a ORDER BY user_id LIMIT ?) b",
                (rs, n) -> new Counts(rs.getInt(1), rs.getInt(2), rs.getInt(3)), params.toArray());
    }

    /** The audience query (one row per person) and its parameters, or {@code null} when nobody can match. */
    private String innerSql(Segment segment, List<Object> params) {
        List<UUID> tenantFilter = null;
        if (segment.needsStores()) {
            tenantFilter = tenantsWithStores(segment);
            if (tenantFilter.isEmpty()) {
                return null;
            }
        }
        StringBuilder sql = new StringBuilder("SELECT DISTINCT ON (u.id) u.id AS user_id, t.id AS tenant_id, "
                + "t.schema_name, u.first_name, u.last_name, COALESCE(NULLIF(btrim(t.designation), ''), "
                + "NULLIF(btrim(t.name), '')) AS company, u.phone_number, u.email "
                + "FROM public.user_tenant_memberships m JOIN public.tenants t ON t.id = m.tenant_id "
                + "JOIN public.users u ON u.id = m.user_id "
                + "WHERE m.role = 'OWNER' AND m.is_active AND u.is_active "
                + "AND t.status NOT IN ('DELETED', 'SUSPENDED') AND t.id <> CAST('" + CrossTenantSql.SYSTEM_TENANT_ID
                + "' AS uuid) AND t.code <> 'KV-ADMIN' AND t.schema_name <> 'public'");
        if (!segment.planCodes().isEmpty()) {
            sql.append(" AND t.plan_type = ANY (string_to_array(?, ','))");
            params.add(String.join(",", segment.planCodes()));
        }
        Set<UUID> ids = new LinkedHashSet<>(segment.tenantIds());
        if (tenantFilter != null) {
            if (!ids.isEmpty()) {
                ids.retainAll(tenantFilter);
                if (ids.isEmpty()) {
                    return null;
                }
            } else {
                ids.addAll(tenantFilter);
            }
        }
        if (!ids.isEmpty()) {
            sql.append(" AND t.id = ANY (CAST(string_to_array(?, ',') AS uuid[]))");
            params.add(String.join(",", ids.stream().map(UUID::toString).toList()));
        }
        return sql + " ORDER BY u.id, t.created_at, t.id";
    }

    /** The live companies with an active store in the countries / activities (validated codes, bound as text). */
    List<UUID> tenantsWithStores(Segment segment) {
        List<TenantBranch> tenants = crossTenant.tenantsWith(null, TenantTable.STORE_PROFILE);
        if (tenants.isEmpty()) {
            return List.of();
        }
        String countries = String.join(",", segment.countries());
        String activities = String.join(",", segment.activities());
        CrossTenantSql.BranchSql branch = (t, params) -> {
            String schema = TenantSchema.validate(t.schemaName());
            StringBuilder b = new StringBuilder("SELECT CAST(" + CrossTenantSql.tenantLiteral(t) + " AS uuid) AS id "
                    + "FROM \"" + schema + "\".stores WHERE is_active");
            if (!countries.isEmpty()) {
                b.append(" AND country = ANY (string_to_array(?, ','))");
                params.add(countries);
            }
            if (!activities.isEmpty()) {
                b.append(" AND category = ANY (string_to_array(?, ','))");
                params.add(activities);
            }
            return b.append(" LIMIT 1").toString();
        };
        UnaryOperator<String> outer = union -> "SELECT id FROM (" + union + ") u";
        return crossTenant.query(tenants, branch, outer, (rs, n) -> rs.getObject(1, UUID.class));
    }
}
