package com.keevo.sync.sync.application.provider;

import com.keevo.sync.sync.domain.port.in.DeltaEntityProvider;
import com.keevo.sync.sync.domain.port.in.DeltaEntityProvider.Page;
import com.keevo.sync.sync.domain.port.in.DeltaEntityProvider.Position;
import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;

@Component
public class AuditEntryDeltaProvider implements DeltaEntityProvider {

    /** Keyset cursor of the delta pull; the {@code audit_log} sync keyset index leads with it (Story 11.5). */
    static final String CURSOR = "occurred_at";

    private final EntityManager em;

    public AuditEntryDeltaProvider(EntityManager em) {
        this.em = em;
    }

    @Override
    public String moduleId() {
        return "core"; // Story 13.10a review: skipped by the pull while DISABLED
    }

    @Override
    public String entityKey() {
        return "auditEntries";
    }

    /** Story v1s-18-7 — the audit log (with justification metadata) is OWNER-only (FR98). */
    @Override
    public boolean ownerOnly() {
        return true;
    }

    @Override
    public Page queryDeltaPage(Instant since, Position after, int limit) {
        // First-time sync: limit to last 30 days
        Instant thirtyDaysAgo = Instant.now().minus(30, ChronoUnit.DAYS);
        Instant effectiveSince = since.isAfter(thirtyDaysAgo) ? since : thirtyDaysAgo;

        var page = DeltaPageQuery.fetch(em,
                "id, user_id, entity_type, entity_id, action, value_before, value_after, occurred_at, "
                        + "store_id, entity_label, device_id, source, CAST(metadata AS text)",
                "audit_log", CURSOR, effectiveSince, after, limit);
        return new Page(page.rows().stream().map(this::mapRow).toList(), page.next());
    }

    private Map<String, Object> mapRow(Object[] row) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", str(row[0]));
        map.put("userId", str(row[1]));
        map.put("entityType", str(row[2]));
        map.put("entityId", str(row[3]));
        map.put("action", str(row[4]));
        map.put("valueBefore", str(row[5]));
        map.put("valueAfter", str(row[6]));
        map.put("occurredAt", ts(row[7]));
        // Story v1s-18-6 — additive enrichment keys (null on rows written before V14)
        map.put("storeId", str(row[8]));
        map.put("entityLabel", str(row[9]));
        map.put("deviceId", str(row[10]));
        map.put("source", str(row[11]));
        map.put("metadata", str(row[12]));
        return map;
    }

    private String str(Object o) { return o != null ? o.toString() : null; }
    private String ts(Object o) { return SyncTimestamps.iso(o); }
}
