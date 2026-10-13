package com.keevo.shared.infrastructure.export;

import com.keevo.shared.application.port.AuditPort;
import com.keevo.shared.application.port.AuditPort.AuditEntryRecord;
import com.keevo.shared.application.port.AuditPort.AuditQuery;
import com.keevo.shared.application.port.export.ExportColumn;
import com.keevo.shared.application.port.export.ExportColumnType;
import com.keevo.shared.application.port.export.ExportDatasetProvider;
import com.keevo.shared.application.port.export.ExportFilter;
import com.keevo.shared.application.port.export.ExportPage;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static com.keevo.shared.infrastructure.export.ExportValues.frLabel;

/**
 * Export dataset {@code audit_log} (« Journal d'audit ») — Story v1s-18-2, FR94 / FR98.
 *
 * <p>Event dataset read through {@link AuditPort#search} / {@link AuditPort#count} so that its
 * rows are exactly those of {@code GET /api/v1/audit} with the same filters, newest first
 * ({@code occurred_at DESC, id DESC}, the audit cursor). Store + period filters plus the criteria
 * {@code userId}, {@code action}, {@code entityType}, {@code entityId}, {@code q} (object label
 * search) of the « Historique des actions » screen. Tenant scoping and read-only transactions come
 * from the audit adapter.
 */
@Component
public class AuditLogExportDatasetProvider implements ExportDatasetProvider {

    static final String KEY = "audit_log";
    public static final String CRITERION_USER_ID = "userId";
    public static final String CRITERION_ACTION = "action";
    public static final String CRITERION_ENTITY_TYPE = "entityType";
    public static final String CRITERION_ENTITY_ID = "entityId";
    public static final String CRITERION_Q = "q";

    private static final Set<String> CRITERIA = Set.of(CRITERION_USER_ID, CRITERION_ACTION,
            CRITERION_ENTITY_TYPE, CRITERION_ENTITY_ID, CRITERION_Q);

    static final Map<String, String> ROLES = Map.of("OWNER", "Propriétaire", "EMPLOYEE", "Employé");
    static final Map<String, String> SOURCES = Map.of("ONLINE", "En ligne", "SYNC", "Synchronisé",
            "SYSTEM", "Système");

    private static final List<ExportColumn> COLUMNS = List.of(
            new ExportColumn("occurredAt", "Date", ExportColumnType.DATETIME),
            new ExportColumn("actor", "Auteur", ExportColumnType.TEXT),
            new ExportColumn("actorRole", "Rôle", ExportColumnType.TEXT),
            new ExportColumn("action", "Action", ExportColumnType.TEXT),
            new ExportColumn("entityType", "Type d'objet", ExportColumnType.TEXT),
            new ExportColumn("entityLabel", "Objet", ExportColumnType.TEXT),
            new ExportColumn("store", "Boutique", ExportColumnType.TEXT),
            new ExportColumn("source", "Source", ExportColumnType.TEXT),
            new ExportColumn("changes", "Changements", ExportColumnType.TEXT),
            new ExportColumn("details", "Détails", ExportColumnType.TEXT));

    private final AuditPort auditPort;

    public AuditLogExportDatasetProvider(AuditPort auditPort) {
        this.auditPort = auditPort;
    }

    @Override public String key() { return KEY; }
    @Override public String label() { return "Journal d'audit"; }
    @Override public List<ExportColumn> columns() { return COLUMNS; }
    @Override public Set<String> criteriaKeys() { return CRITERIA; }

    @Override
    public long count(ExportFilter filter) {
        return auditPort.count(query(filter));
    }

    @Override
    public ExportPage readPage(ExportFilter filter, String cursor, int limit) {
        if (limit < 1) throw new IllegalArgumentException("limit must be >= 1");
        AuditPort.AuditSlice slice = auditPort.search(query(filter), cursor, 0, limit);
        List<List<Object>> rows = new ArrayList<>(slice.entries().size());
        for (AuditEntryRecord e : slice.entries()) {
            rows.add(row(e));
        }
        return new ExportPage(rows, slice.hasMore() ? slice.nextCursor() : null);
    }

    /** Same query as {@code GET /api/v1/audit} with the same filters (blank criteria ignored). */
    static AuditQuery query(ExportFilter filter) {
        ExportFilter f = filter != null ? filter : ExportFilter.none();
        return new AuditQuery(f.from(), f.to(), f.storeId(),
                uuid(f.criterion(CRITERION_USER_ID)),
                blankToNull(f.criterion(CRITERION_ACTION)),
                blankToNull(f.criterion(CRITERION_ENTITY_TYPE)),
                uuid(f.criterion(CRITERION_ENTITY_ID)),
                blankToNull(f.criterion(CRITERION_Q)));
    }

    /**
     * One readable row (v1s-16-20): French action / object type / source, before→after rendered by
     * {@link AuditChangesFormatter}; no id, device id or JSON.
     */
    static List<Object> row(AuditEntryRecord e) {
        String actor = e.actorName() != null && !e.actorName().isBlank() ? e.actorName() : e.actorPhone();
        String label = e.entityLabel() != null && !ExportValues.isTechnical(e.entityLabel()) ? e.entityLabel() : null;
        return Arrays.asList(e.occurredAt(), actor, roleLabel(e.actorRole()), frLabel(AuditLabels.ACTIONS, e.action()),
                frLabel(AuditLabels.ENTITY_TYPES, e.entityType()), label, e.storeName(),
                frLabel(SOURCES, e.source()), AuditChangesFormatter.changes(e.valueBefore(), e.valueAfter()),
                AuditChangesFormatter.details(e.metadata(), e.valueAfter()));
    }

    private static String roleLabel(String role) {
        return role == null ? null : ROLES.get(role); // other platform roles: no label
    }

    private static UUID uuid(String value) {
        String v = blankToNull(value);
        return v != null ? UUID.fromString(v) : null; // validated upstream (400 VALIDATION_ERROR)
    }

    private static String blankToNull(String value) {
        if (value == null) return null;
        String t = value.trim();
        return t.isEmpty() ? null : t;
    }
}
