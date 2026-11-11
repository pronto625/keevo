package com.keevo.admin.notification.adapter.out.persistence;

import com.keevo.admin.crosstenant.CrossTenantSql;
import com.keevo.admin.crosstenant.TenantBranch;
import com.keevo.admin.crosstenant.TenantTable;
import com.keevo.admin.notification.domain.model.GlobalNotificationTarget;
import com.keevo.admin.notification.domain.model.PushTargetTenant;
import com.keevo.admin.notification.domain.model.PushTenantRef;
import com.keevo.admin.notification.domain.port.out.PushAudiencePort;
import com.keevo.shared.infrastructure.persistence.TenantSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.RecoverableDataAccessException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiConsumer;

/**
 * JdbcPushAudienceAdapter — audience of a global push (Story 9-5, FR82).
 *
 * <p>Not {@code @Transactional}: a failing statement of one company must not poison the others. Schema
 * names come from {@code public.tenants.schema_name} and are gated by {@link TenantSchema#isValid}
 * before interpolation; a company whose schema / table cannot be read is skipped (id only in logs),
 * connection or resource failures are rethrown.
 *
 * <p>Story 19.11c (FUS-NFR11): the device tokens are read through {@link CrossTenantSql} — the companies' table
 * presence in one catalogue query, then one {@code UNION ALL} per 100 companies (a failing chunk retried company by
 * company) — never a statement per company.
 */
@Component
public class JdbcPushAudienceAdapter implements PushAudiencePort {

    private static final Logger log = LoggerFactory.getLogger(JdbcPushAudienceAdapter.class);

    static final String SYSTEM_TENANT_ID = "00000000-0000-0000-0000-000000000000";

    /** Companies that can receive a global push: not DELETED / SUSPENDED, never KV-ADMIN. */
    static final String REACHABLE = "status NOT IN ('DELETED', 'SUSPENDED') AND id <> '" + SYSTEM_TENANT_ID
            + "'::uuid AND code <> 'KV-ADMIN' AND schema_name <> 'public'";

    private final JdbcTemplate jdbc;
    private final CrossTenantSql crossTenant;

    /** With cross-tenant statements of the default chunk size (tests). */
    public JdbcPushAudienceAdapter(JdbcTemplate jdbc) {
        this(jdbc, new CrossTenantSql(jdbc, CrossTenantSql.DEFAULT_CHUNK_SIZE));
    }

    @org.springframework.beans.factory.annotation.Autowired
    public JdbcPushAudienceAdapter(JdbcTemplate jdbc, CrossTenantSql crossTenant) {
        this.jdbc = jdbc;
        this.crossTenant = crossTenant;
    }

    private static final RowMapper<PushTargetTenant> TENANT =
            (rs, i) -> new PushTargetTenant(rs.getObject("id", UUID.class), rs.getString("schema_name"));

    @Override
    public List<PushTargetTenant> targetTenants(GlobalNotificationTarget target, UUID tenantId) {
        List<PushTargetTenant> rows = switch (target) {
            case ALL -> jdbc.query("SELECT id, schema_name FROM public.tenants WHERE " + REACHABLE
                    + " ORDER BY created_at, id", TENANT);
            // Story 19.7a: every plan of the target's kind (joined on the catalogue)
            case PREMIUM, TRIAL, FREE -> jdbc.query("SELECT id, schema_name FROM public.tenants WHERE " + REACHABLE
                    + " AND plan_type IN (SELECT code FROM public.plans WHERE kind = ?) ORDER BY created_at, id",
                    TENANT, target.planKind().name());
            case TENANT -> tenantId == null ? List.of() : jdbc.query(
                    "SELECT id, schema_name FROM public.tenants WHERE " + REACHABLE + " AND id = ?", TENANT, tenantId);
        };
        return rows.stream().filter(JdbcPushAudienceAdapter::validSchema).toList();
    }

    @Override
    public Optional<PushTargetTenant> reachableTenant(UUID tenantId) {
        return targetTenants(GlobalNotificationTarget.TENANT, tenantId).stream().findFirst();
    }

    @Override
    public List<PushTenantRef> searchReachable(String search, int limit) {
        String term = search == null ? "" : search.strip();
        String pattern = "%" + term.toLowerCase(Locale.ROOT)
                .replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
        return jdbc.query("SELECT id, name, plan_type FROM public.tenants WHERE " + REACHABLE
                        + " AND LOWER(name) LIKE ? ORDER BY name, id LIMIT ?",
                (rs, i) -> new PushTenantRef(rs.getObject("id", UUID.class), rs.getString("name"),
                        rs.getString("plan_type")),
                pattern, limit);
    }

    @Override
    public Optional<PushTenantRef> tenantOfSchema(String schemaName) {
        if (!TenantSchema.isValid(schemaName)) return Optional.empty();
        return jdbc.query("SELECT id, name, plan_type FROM public.tenants WHERE schema_name = ? AND status <> 'DELETED'",
                (rs, i) -> new PushTenantRef(rs.getObject("id", UUID.class), rs.getString("name"),
                        rs.getString("plan_type")),
                schemaName).stream().findFirst();
    }

    @Override
    public List<String> deviceTokens(PushTargetTenant tenant) {
        List<String> tokens = new ArrayList<>();
        forEachTenantTokens(List.of(tenant), (t, list) -> tokens.addAll(list));
        return tokens;
    }

    @Override
    public long countDeviceTokens(List<PushTargetTenant> tenants) {
        // A device registered in several companies (same FCM token) counts once, as it receives the push once:
        // distinct per statement in SQL, then across statements here.
        Set<String> distinct = new HashSet<>();
        crossTenant.forEachChunk(branches(tenants), (tenant, params) -> "SELECT token FROM "
                        + TenantSchema.validate(tenant.schemaName()) + ".device_tokens",
                union -> "SELECT DISTINCT token FROM (" + union + ") u", (rs, i) -> rs.getString("token"),
                distinct::addAll);
        return distinct.size();
    }

    @Override
    public void forEachTenantTokens(List<PushTargetTenant> tenants,
                                    BiConsumer<PushTargetTenant, List<String>> action) {
        Map<String, PushTargetTenant> byId = new HashMap<>();
        Map<String, Integer> position = new HashMap<>();
        for (PushTargetTenant t : tenants) {
            String id = t.id().toString();
            byId.putIfAbsent(id, t);
            position.putIfAbsent(id, position.size());
        }
        crossTenant.forEachChunk(branches(tenants), (tenant, params) -> "SELECT " + position.get(tenant.id())
                        + " AS pos, " + CrossTenantSql.tenantLiteral(tenant) + "::text AS tenant_id, token FROM "
                        + TenantSchema.validate(tenant.schemaName()) + ".device_tokens",
                union -> "SELECT tenant_id, token FROM (" + union + ") u ORDER BY pos, token",
                (rs, i) -> new String[]{rs.getString("tenant_id"), rs.getString("token")},
                rows -> {
                    Map<String, List<String>> grouped = new LinkedHashMap<>();
                    for (String[] row : rows) {
                        grouped.computeIfAbsent(row[0], k -> new ArrayList<>()).add(row[1]);
                    }
                    grouped.forEach((id, tokens) -> action.accept(byId.get(id), tokens));
                });
    }

    /** The companies holding the device tokens table (presence in one catalogue query), each once, order kept. */
    private List<TenantBranch> branches(List<PushTargetTenant> tenants) {
        return crossTenant.withPresence(tenants.stream()
                        .filter(JdbcPushAudienceAdapter::validSchema)
                        .map(t -> new TenantBranch(t.id().toString(), null, t.schemaName(), null, null))
                        .distinct()
                        .toList())
                .stream().filter(t -> t.has(TenantTable.DEVICE_TOKENS)).toList();
    }

    private static boolean validSchema(PushTargetTenant t) {
        if (TenantSchema.isValid(t.schemaName())) return true;
        log.warn("Global push: company {} skipped (invalid schema name)", t.id());
        return false;
    }
}
