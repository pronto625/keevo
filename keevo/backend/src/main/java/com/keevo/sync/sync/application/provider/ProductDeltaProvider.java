package com.keevo.sync.sync.application.provider;

import com.keevo.sync.sync.domain.port.in.DeltaEntityProvider;
import com.keevo.sync.sync.domain.port.in.DeltaEntityProvider.Page;
import com.keevo.sync.sync.domain.port.in.DeltaEntityProvider.Position;
import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.*;

@Component
public class ProductDeltaProvider implements DeltaEntityProvider {

    private final EntityManager em;

    public ProductDeltaProvider(EntityManager em) {
        this.em = em;
    }

    @Override
    public String entityKey() {
        return "products";
    }

    @Override
    public Page queryDeltaPage(Instant since, Position after, int limit) {
        var page = DeltaPageQuery.fetch(em,
                "id, name, price, buy_price, transport_cost, sku, category_id, description, photo_url, archived, status, stock_quantity, created_at, updated_at",
                "products", "updated_at", since, after, limit);
        return new Page(page.rows().stream().map(this::mapRow).toList(), page.next());
    }

    private Map<String, Object> mapRow(Object[] row) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", str(row[0]));
        map.put("name", str(row[1]));
        map.put("price", DeltaValues.num(row[2]));
        map.put("buyPrice", DeltaValues.num(row[3]));
        map.put("transportCost", DeltaValues.num(row[4]));
        map.put("sku", str(row[5]));
        map.put("categoryId", str(row[6]));
        map.put("description", str(row[7]));
        map.put("photoUrl", str(row[8]));
        map.put("archived", row[9]);
        map.put("status", str(row[10]));
        map.put("stockQuantity", DeltaValues.num(row[11]));
        map.put("createdAt", ts(row[12]));
        map.put("updatedAt", ts(row[13]));
        return map;
    }

    private String str(Object o) { return o != null ? o.toString() : null; }
    private String ts(Object o) { return o != null ? o.toString() : null; }
}
