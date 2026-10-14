package com.keevo.sync.sync.application.provider;

import com.keevo.sync.sync.domain.port.in.DeltaEntityProvider;
import com.keevo.sync.sync.domain.port.in.DeltaEntityProvider.Page;
import com.keevo.sync.sync.domain.port.in.DeltaEntityProvider.Position;
import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.*;

@Component
public class ReportsDeltaProvider implements DeltaEntityProvider {

    /**
     * Keyset cursor of the delta pull; the {@code reports} sync keyset index leads with it (Story 11.5).
     * updated_at, not created_at: a later delivery attempt (status, attempts) or a regeneration
     * must reach the other devices. EndOfDayReportJpaEntity stamps it on every write.
     */
    static final String CURSOR = "updated_at";

    private final EntityManager em;

    public ReportsDeltaProvider(EntityManager em) {
        this.em = em;
    }

    @Override
    public String entityKey() {
        return "reports";
    }

    @Override
    public Page queryDeltaPage(Instant since, Position after, int limit) {
        var page = DeltaPageQuery.fetch(em,
                "id, tenant_id, store_id, store_name, actor_id, actor_name, report_type, report_date, content, delivery_status, delivery_attempts, last_attempt_at, total_revenue, total_sales, is_automatic, created_at",
                "reports", CURSOR, since, after, limit);
        return new Page(page.rows().stream().map(this::mapRow).toList(), page.next());
    }

    private Map<String, Object> mapRow(Object[] row) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", str(row[0]));
        map.put("tenantId", str(row[1]));
        map.put("storeId", str(row[2]));
        map.put("storeName", str(row[3]));
        map.put("actorId", str(row[4]));
        map.put("actorName", str(row[5]));
        map.put("reportType", str(row[6]));
        map.put("reportDate", str(row[7]));
        map.put("content", str(row[8]));
        map.put("deliveryStatus", str(row[9]));
        map.put("deliveryAttempts", DeltaValues.num(row[10]));
        map.put("lastAttemptAt", ts(row[11]));
        map.put("totalRevenue", DeltaValues.num(row[12]));
        map.put("totalSales", DeltaValues.num(row[13]));
        map.put("isAutomatic", DeltaValues.bool(row[14]));
        map.put("createdAt", ts(row[15]));
        return map;
    }

    private String str(Object o) { return o != null ? o.toString() : null; }
    private String ts(Object o) { return SyncTimestamps.iso(o); }
}
