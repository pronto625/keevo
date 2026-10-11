package com.keevo.sync.sync.application.provider;

import com.keevo.sync.sync.domain.port.in.DeltaEntityProvider;
import com.keevo.sync.sync.domain.port.in.DeltaEntityProvider.Page;
import com.keevo.sync.sync.domain.port.in.DeltaEntityProvider.Position;
import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.*;

@Component
public class DayClosureDeltaProvider implements DeltaEntityProvider {

    private final EntityManager em;

    public DayClosureDeltaProvider(EntityManager em) {
        this.em = em;
    }

    @Override
    public String entityKey() {
        return "dayClosures";
    }

    @Override
    public Page queryDeltaPage(Instant since, Position after, int limit) {
        var page = DeltaPageQuery.fetch(em,
                "id, store_id, actor_id, closed_at, total_sales, total_revenue, cash_amount, momo_amount, is_automatic",
                "day_closures", "received_at", since, after, limit);
        return new Page(page.rows().stream().map(this::mapRow).toList(), page.next());
    }

    private Map<String, Object> mapRow(Object[] row) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", str(row[0]));
        map.put("storeId", str(row[1]));
        map.put("employeeId", str(row[2]));
        map.put("closedAt", ts(row[3]));
        map.put("totalSales", DeltaValues.num(row[4]));
        map.put("totalRevenue", DeltaValues.num(row[5]));
        map.put("cashTotal", DeltaValues.num(row[6]));
        map.put("mobileMoneyTotal", DeltaValues.num(row[7]));
        map.put("isAutomatic", row.length > 8 ? DeltaValues.bool(row[8]) : false);
        map.put("createdAt", ts(row[3])); // closedAt serves as createdAt
        return map;
    }

    private String str(Object o) { return o != null ? o.toString() : null; }
    private String ts(Object o) { return o != null ? o.toString() : null; }
}
