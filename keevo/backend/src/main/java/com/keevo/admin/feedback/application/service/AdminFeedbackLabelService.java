package com.keevo.admin.feedback.application.service;

import com.keevo.admin.AdminLabels;
import com.keevo.admin.crosstenant.CrossTenantSql;
import com.keevo.admin.crosstenant.TenantBranch;
import com.keevo.admin.crosstenant.TenantTable;
import com.keevo.feedback.api.FeedbackView;
import com.keevo.shared.infrastructure.persistence.TenantSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * AdminFeedbackLabelService — readable names for the « Retours utilisateurs » inbox (spec 9-1
 * completion): the business name of the feedback's company and the submitter's label.
 *
 * <p>{@code feedback.tenant_id} holds the tenant's schema name. For one page of feedback:
 * one query on {@code public.tenants} (by schema name), one on the OWNER memberships, then the
 * employee names of every company of the page in one table-presence query and one {@code UNION ALL}
 * per 100 companies (Story 19.11c, FUS-NFR11 — was one statement per company).
 *
 * <p>Per-schema errors (dropped / broken schema) only lose that company's employee names;
 * connection or resource failures are rethrown.
 */
@Service
public class AdminFeedbackLabelService {

    private static final Logger log = LoggerFactory.getLogger(AdminFeedbackLabelService.class);

    static final String DELETED_TENANT = AdminLabels.DELETED_TENANT;
    static final String UNNAMED_TENANT = AdminLabels.UNNAMED_TENANT;
    static final String OWNER = AdminLabels.OWNER;
    static final String UNKNOWN_USER = AdminLabels.UNKNOWN_USER;

    private final JdbcTemplate jdbc;
    private final CrossTenantSql crossTenant;

    /** With cross-tenant statements of the default chunk size (tests). */
    public AdminFeedbackLabelService(JdbcTemplate jdbc) {
        this(jdbc, new CrossTenantSql(jdbc, CrossTenantSql.DEFAULT_CHUNK_SIZE));
    }

    @org.springframework.beans.factory.annotation.Autowired
    public AdminFeedbackLabelService(JdbcTemplate jdbc, CrossTenantSql crossTenant) {
        this.jdbc = jdbc;
        this.crossTenant = crossTenant;
    }

    /** Labels of one feedback: company name and submitter label. */
    public record FeedbackLabels(String tenantName, String userLabel) {}

    /**
     * Labels keyed by feedback id, for every feedback of the list. Deliberately NOT
     * {@code @Transactional}: a broken company schema must not abort (25P02) the next ones.
     */
    public Map<UUID, FeedbackLabels> labels(Collection<FeedbackView> feedback) {
        if (feedback.isEmpty()) return Map.of();

        Set<String> schemas = feedback.stream().map(FeedbackView::tenantSchema)
                .filter(TenantSchema::isValid)
                .collect(Collectors.toCollection(LinkedHashSet::new));

        Map<String, String> tenantNames = new HashMap<>(); // live (non-deleted) tenants only
        Map<String, String> tenantIds = new HashMap<>(); // schema → tenant id
        Map<String, Set<UUID>> owners = new HashMap<>();
        Map<String, Map<UUID, String>> employeeNames = new HashMap<>();

        if (!schemas.isEmpty()) {
            String in = placeholders(schemas.size());
            jdbc.query("SELECT id::text AS id, schema_name, name FROM public.tenants "
                            + "WHERE status <> 'DELETED' AND schema_name IN (" + in + ")",
                    (RowCallbackHandler) rs -> {
                        String name = rs.getString("name");
                        tenantNames.put(rs.getString("schema_name"),
                                name == null || name.isBlank() ? UNNAMED_TENANT : name.trim());
                        tenantIds.put(rs.getString("schema_name"), rs.getString("id"));
                    },
                    schemas.toArray());

            if (!tenantNames.isEmpty()) {
                jdbc.query("SELECT t.schema_name, m.user_id FROM public.user_tenant_memberships m "
                                + "JOIN public.tenants t ON t.id = m.tenant_id "
                                + "WHERE m.role = 'OWNER' AND t.schema_name IN ("
                                + placeholders(tenantNames.size()) + ")",
                        (RowCallbackHandler) rs -> owners
                                .computeIfAbsent(rs.getString("schema_name"), k -> new HashSet<>())
                                .add(UUID.fromString(rs.getString("user_id"))),
                        tenantNames.keySet().toArray());
            }

            Map<String, Set<UUID>> usersBySchema = new LinkedHashMap<>();
            for (FeedbackView f : feedback) {
                if (tenantNames.containsKey(f.tenantSchema())) {
                    usersBySchema.computeIfAbsent(f.tenantSchema(), k -> new LinkedHashSet<>()).add(f.userId());
                }
            }
            employeeNames.putAll(loadEmployeeNames(usersBySchema, tenantIds));
        }

        Map<UUID, FeedbackLabels> out = new HashMap<>();
        for (FeedbackView f : feedback) {
            String schema = f.tenantSchema();
            String tenantName = tenantNames.get(schema);
            if (tenantName == null) {
                out.put(f.id(), new FeedbackLabels(DELETED_TENANT, UNKNOWN_USER));
                continue;
            }
            String name = employeeNames.getOrDefault(schema, Map.of()).get(f.userId());
            String user = name != null ? name
                    : owners.getOrDefault(schema, Set.of()).contains(f.userId()) ? OWNER
                    : UNKNOWN_USER;
            out.put(f.id(), new FeedbackLabels(tenantName, user));
        }
        return out;
    }

    /**
     * Names (trimmed first + last, earliest employee row first) of the given users, by company schema: one presence
     * query, then one union per 100 companies, each branch bound to its own users. A company whose schema cannot be
     * read only loses its names (logged by {@link CrossTenantSql}); connection / resource failures are rethrown.
     */
    private Map<String, Map<UUID, String>> loadEmployeeNames(Map<String, Set<UUID>> usersBySchema,
                                                             Map<String, String> tenantIds) {
        Map<String, String> schemaById = new HashMap<>();
        List<TenantBranch> branches = new ArrayList<>();
        usersBySchema.keySet().forEach(schema -> {
            String id = tenantIds.get(schema);
            if (id == null || !TenantSchema.isValid(schema)) return; // gate before SQL interpolation
            schemaById.put(id, schema);
            branches.add(new TenantBranch(id, null, schema, null, null));
        });
        Map<String, Map<UUID, String>> names = new HashMap<>();
        crossTenant.query(crossTenant.withPresence(branches).stream()
                        .filter(t -> t.has(TenantTable.EMPLOYEE_NAMES)).toList(),
                (tenant, params) -> {
                    Set<UUID> users = usersBySchema.get(schemaById.get(tenant.id()));
                    params.addAll(users);
                    return "SELECT " + CrossTenantSql.tenantLiteral(tenant) + "::text AS tenant_id, user_id, "
                            + "NULLIF(TRIM(CONCAT_WS(' ', TRIM(first_name), TRIM(last_name))), '') AS full_name, "
                            + "created_at FROM " + TenantSchema.validate(tenant.schemaName()) + ".employees "
                            + "WHERE user_id IN (" + placeholders(users.size()) + ")";
                },
                union -> "SELECT tenant_id, user_id, full_name FROM (" + union + ") u ORDER BY created_at",
                (rs, n) -> new String[]{rs.getString("tenant_id"), rs.getString("user_id"), rs.getString("full_name")})
                .forEach(row -> {
                    if (row[2] != null) {
                        names.computeIfAbsent(schemaById.get(row[0]), k -> new HashMap<>())
                                .putIfAbsent(UUID.fromString(row[1]), row[2]);
                    }
                });
        return names;
    }

    private static String placeholders(int n) {
        return String.join(",", java.util.Collections.nCopies(n, "?"));
    }
}
