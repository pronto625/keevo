package com.keevo.catalog.product.adapter.out.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.keevo.catalog.product.domain.port.out.ProductDerivationRepository;
import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Story 20.6 — {@link ProductDerivationRepository} with native SQL on the Hibernate session's connection (already
 * routed to the tenant schema, same transaction as the caller). Lists are bound as one JSON parameter
 * ({@code jsonb_array_elements_text} / {@code jsonb_to_recordset}): one statement and one plan whatever their size, no
 * escaping of free text in an array literal, no bind-parameter limit.
 */
@Component
public class JpaProductDerivationRepository implements ProductDerivationRepository {

    private static final String FIND_CANDIDATES =
            "SELECT TRUE, p.product_key, CAST(p.id AS text), p.archived, p.price, p.price_manual, "
                    + "CAST(p.merged_into_id AS text), p.created_at FROM products p "
                    + "WHERE p.product_key IN (SELECT jsonb_array_elements_text(CAST(:keys AS jsonb))) "
                    + "UNION ALL "
                    + "SELECT FALSE, r.n, CAST(p.id AS text), p.archived, p.price, p.price_manual, "
                    + "CAST(p.merged_into_id AS text), p.created_at "
                    + "FROM jsonb_array_elements_text(CAST(:names AS jsonb)) AS r(n) "
                    + "JOIN products p ON lower(p.name) = lower(r.n)";

    private static final String FIND_TARGETS =
            "SELECT CAST(p.id AS text), p.archived, p.price, p.price_manual FROM products p "
                    + "WHERE p.id IN (SELECT CAST(jsonb_array_elements_text(CAST(:ids AS jsonb)) AS uuid))";

    private static final String UPDATE_PRICES =
            "UPDATE products p SET price = v.price, updated_at = :now "
                    + "FROM jsonb_to_recordset(CAST(:rows AS jsonb)) AS v(id uuid, price integer) WHERE p.id = v.id";

    private static final String EXISTING_SKUS =
            "SELECT p.sku FROM products p WHERE p.sku IN (SELECT jsonb_array_elements_text(CAST(:skus AS jsonb)))";

    private static final String INSERT_ALL =
            "INSERT INTO products (id, name, sku, price, buy_price, transport_cost, stock_quantity, minimum_threshold, "
                    + "track_stock, barcode, product_key, price_manual, archived, status, created_at, updated_at) "
                    + "SELECT v.id, v.name, v.sku, v.price, 0, 0, 0, 0, FALSE, v.barcode, v.product_key, FALSE, FALSE, "
                    + "'ACTIVE', :now, :now FROM jsonb_to_recordset(CAST(:rows AS jsonb)) "
                    + "AS v(id uuid, name text, sku text, price integer, barcode text, product_key text) "
                    + "ON CONFLICT DO NOTHING RETURNING CAST(id AS text)";

    private final EntityManager entityManager;
    private final ObjectMapper json;

    public JpaProductDerivationRepository(EntityManager entityManager, ObjectMapper json) {
        this.entityManager = entityManager;
        this.json = json;
    }

    @Override
    @SuppressWarnings("unchecked")
    public List<Candidate> findCandidates(Collection<String> keys, Collection<String> names) {
        List<Object[]> rows = entityManager.createNativeQuery(FIND_CANDIDATES)
                .setParameter("keys", toJson(keys))
                .setParameter("names", toJson(names))
                .getResultList();
        List<Candidate> candidates = new ArrayList<>(rows.size());
        for (Object[] r : rows) {
            candidates.add(new Candidate(Boolean.TRUE.equals(r[0]), (String) r[1], UUID.fromString((String) r[2]),
                    Boolean.TRUE.equals(r[3]), ((Number) r[4]).intValue(), Boolean.TRUE.equals(r[5]),
                    r[6] == null ? null : UUID.fromString((String) r[6]), instant(r[7])));
        }
        return candidates;
    }

    @Override
    @SuppressWarnings("unchecked")
    public Map<UUID, Target> findTargets(Collection<UUID> ids) {
        if (ids.isEmpty()) return Map.of();
        List<Object[]> rows = entityManager.createNativeQuery(FIND_TARGETS)
                .setParameter("ids", toJson(ids.stream().map(UUID::toString).toList()))
                .getResultList();
        Map<UUID, Target> targets = new HashMap<>();
        for (Object[] r : rows) {
            UUID id = UUID.fromString((String) r[0]);
            targets.put(id, new Target(id, Boolean.TRUE.equals(r[1]), ((Number) r[2]).intValue(),
                    Boolean.TRUE.equals(r[3])));
        }
        return targets;
    }

    @Override
    public void updatePrices(Map<UUID, Integer> prices, Instant now) {
        if (prices.isEmpty()) return;
        List<Map<String, Object>> rows = new ArrayList<>(prices.size());
        prices.forEach((id, price) -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", id.toString());
            row.put("price", price);
            rows.add(row);
        });
        entityManager.createNativeQuery(UPDATE_PRICES)
                .setParameter("rows", toJson(rows))
                .setParameter("now", now)
                .executeUpdate();
    }

    @Override
    @SuppressWarnings("unchecked")
    public List<String> existingSkus(Collection<String> skus) {
        if (skus.isEmpty()) return List.of();
        return entityManager.createNativeQuery(EXISTING_SKUS).setParameter("skus", toJson(skus)).getResultList();
    }

    @Override
    @SuppressWarnings("unchecked")
    public List<UUID> insertAll(List<NewProduct> products, Instant now) {
        if (products.isEmpty()) return List.of();
        List<Map<String, Object>> rows = new ArrayList<>(products.size());
        for (NewProduct p : products) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", p.id().toString());
            row.put("name", p.name());
            row.put("sku", p.sku());
            row.put("price", p.price());
            row.put("barcode", p.barcode());
            row.put("product_key", p.productKey());
            rows.add(row);
        }
        List<String> created = entityManager.createNativeQuery(INSERT_ALL)
                .setParameter("rows", toJson(rows))
                .setParameter("now", now)
                .getResultList();
        return created.stream().map(UUID::fromString).toList();
    }

    private String toJson(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot serialise the derivation parameters", e);
        }
    }

    private static Instant instant(Object value) {
        if (value == null) return Instant.EPOCH;
        if (value instanceof Instant i) return i;
        if (value instanceof OffsetDateTime o) return o.toInstant();
        if (value instanceof Timestamp t) return t.toInstant();
        return Instant.EPOCH;
    }
}
