package com.keevo.admin.feedback.application.service;

import com.keevo.admin.AdminLabels;
import com.keevo.feedback.feedback.domain.model.Feedback;
import com.keevo.shared.infrastructure.persistence.TenantSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.RecoverableDataAccessException;
import org.springframework.dao.TransientDataAccessException;
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
 * one query on {@code public.tenants} (by schema name), one on the OWNER memberships, then one
 * {@code <schema>.employees} query per distinct schema (bounded by the page size).
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

    public AdminFeedbackLabelService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Labels of one feedback: company name and submitter label. */
    public record FeedbackLabels(String tenantName, String userLabel) {}

    /**
     * Labels keyed by feedback id, for every feedback of the list. Deliberately NOT
     * {@code @Transactional}: a broken company schema must not abort (25P02) the next ones.
     */
    public Map<UUID, FeedbackLabels> labels(Collection<Feedback> feedback) {
        if (feedback.isEmpty()) return Map.of();

        Set<String> schemas = feedback.stream().map(Feedback::getTenantId)
                .filter(TenantSchema::isValid)
                .collect(Collectors.toCollection(LinkedHashSet::new));

        Map<String, String> tenantNames = new HashMap<>(); // live (non-deleted) tenants only
        Map<String, Set<UUID>> owners = new HashMap<>();
        Map<String, Map<UUID, String>> employeeNames = new HashMap<>();

        if (!schemas.isEmpty()) {
            String in = placeholders(schemas.size());
            jdbc.query("SELECT schema_name, name FROM public.tenants "
                            + "WHERE status <> 'DELETED' AND schema_name IN (" + in + ")",
                    (RowCallbackHandler) rs -> {
                        String name = rs.getString("name");
                        tenantNames.put(rs.getString("schema_name"),
                                name == null || name.isBlank() ? UNNAMED_TENANT : name.trim());
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
            for (Feedback f : feedback) {
                if (tenantNames.containsKey(f.getTenantId())) {
                    usersBySchema.computeIfAbsent(f.getTenantId(), k -> new LinkedHashSet<>()).add(f.getUserId());
                }
            }
            usersBySchema.forEach((schema, users) ->
                    employeeNames.put(schema, loadEmployeeNames(schema, users)));
        }

        Map<UUID, FeedbackLabels> out = new HashMap<>();
        for (Feedback f : feedback) {
            String schema = f.getTenantId();
            String tenantName = tenantNames.get(schema);
            if (tenantName == null) {
                out.put(f.getId(), new FeedbackLabels(DELETED_TENANT, UNKNOWN_USER));
                continue;
            }
            String name = employeeNames.getOrDefault(schema, Map.of()).get(f.getUserId());
            String user = name != null ? name
                    : owners.getOrDefault(schema, Set.of()).contains(f.getUserId()) ? OWNER
                    : UNKNOWN_USER;
            out.put(f.getId(), new FeedbackLabels(tenantName, user));
        }
        return out;
    }

    private Map<UUID, String> loadEmployeeNames(String schema, Set<UUID> users) {
        Map<UUID, String> names = new HashMap<>();
        if (!TenantSchema.isValid(schema)) return names; // gate before SQL interpolation
        List<Object> args = new ArrayList<>(users);
        try {
            jdbc.query("SELECT user_id, NULLIF(TRIM(CONCAT_WS(' ', TRIM(first_name), TRIM(last_name))), '') AS full_name "
                            + "FROM " + schema + ".employees WHERE user_id IN (" + placeholders(users.size()) + ") "
                            + "ORDER BY created_at",
                    (RowCallbackHandler) rs -> {
                        String fullName = rs.getString("full_name");
                        if (fullName != null) {
                            names.putIfAbsent(UUID.fromString(rs.getString("user_id")), fullName);
                        }
                    },
                    args.toArray());
        } catch (DataAccessException e) {
            if (e instanceof DataAccessResourceFailureException
                    || e instanceof TransientDataAccessException
                    || e instanceof RecoverableDataAccessException) {
                throw e;
            }
            // Schema / SQL error of this company only — never the schema name or SQL in logs.
            log.warn("Feedback inbox: employee names of one company unreadable ({})", e.getClass().getSimpleName());
        }
        return names;
    }

    private static String placeholders(int n) {
        return String.join(",", java.util.Collections.nCopies(n, "?"));
    }
}
