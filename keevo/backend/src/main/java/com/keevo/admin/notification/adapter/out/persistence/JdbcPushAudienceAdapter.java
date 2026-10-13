package com.keevo.admin.notification.adapter.out.persistence;

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

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * JdbcPushAudienceAdapter — audience of a global push (Story 9-5, FR82).
 *
 * <p>Not {@code @Transactional}: a failing statement of one company must not poison the others. Schema
 * names come from {@code public.tenants.schema_name} and are gated by {@link TenantSchema#isValid}
 * before interpolation; a company whose schema / table cannot be read is skipped (id only in logs),
 * connection or resource failures are rethrown.
 */
@Component
public class JdbcPushAudienceAdapter implements PushAudiencePort {

    private static final Logger log = LoggerFactory.getLogger(JdbcPushAudienceAdapter.class);

    static final String SYSTEM_TENANT_ID = "00000000-0000-0000-0000-000000000000";

    /** Companies that can receive a global push: not DELETED / SUSPENDED, never KV-ADMIN. */
    static final String REACHABLE = "status NOT IN ('DELETED', 'SUSPENDED') AND id <> '" + SYSTEM_TENANT_ID
            + "'::uuid AND code <> 'KV-ADMIN' AND schema_name <> 'public'";

    private final JdbcTemplate jdbc;

    public JdbcPushAudienceAdapter(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<PushTargetTenant> TENANT =
            (rs, i) -> new PushTargetTenant(rs.getObject("id", UUID.class), rs.getString("schema_name"));

    @Override
    public List<PushTargetTenant> targetTenants(GlobalNotificationTarget target, UUID tenantId) {
        List<PushTargetTenant> rows = switch (target) {
            case ALL -> jdbc.query("SELECT id, schema_name FROM public.tenants WHERE " + REACHABLE
                    + " ORDER BY created_at, id", TENANT);
            case PREMIUM, TRIAL, FREE -> jdbc.query("SELECT id, schema_name FROM public.tenants WHERE " + REACHABLE
                    + " AND plan_type = ? ORDER BY created_at, id", TENANT, target.planType());
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
        try {
            return jdbc.queryForList("SELECT token FROM " + tenant.schemaName() + ".device_tokens ORDER BY token",
                    String.class);
        } catch (DataAccessResourceFailureException | TransientDataAccessException
                 | RecoverableDataAccessException e) {
            throw e; // outage / pool exhaustion: not a problem of this company
        } catch (DataAccessException e) {
            log.warn("Global push: company {} skipped ({})", tenant.id(), e.getClass().getSimpleName());
            return List.of();
        }
    }

    @Override
    public long countDeviceTokens(List<PushTargetTenant> tenants) {
        // One statement per company so a broken schema only drops its own tokens; a device registered in
        // several companies (same FCM token) counts once, as it receives the push once.
        Set<String> distinct = new HashSet<>();
        for (PushTargetTenant tenant : tenants) {
            distinct.addAll(deviceTokens(tenant));
        }
        return distinct.size();
    }

    private static boolean validSchema(PushTargetTenant t) {
        if (TenantSchema.isValid(t.schemaName())) return true;
        log.warn("Global push: company {} skipped (invalid schema name)", t.id());
        return false;
    }
}
