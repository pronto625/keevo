package com.keevo.store.store.adapter.out.export;

import com.keevo.shared.application.port.export.ExportColumn;
import com.keevo.shared.application.port.export.ExportColumnType;
import com.keevo.shared.application.port.export.ExportDatasetProvider;
import com.keevo.shared.application.port.export.ExportFilter;
import com.keevo.shared.application.port.export.ExportPage;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * StoresExportDatasetProvider — demo export dataset {@code stores} (« Boutiques »), Story v1s-18-1.
 *
 * <p>Proves the export chain end to end; the business catalogue arrives with v1s-18-2. Store
 * filter on {@code id}, period filter on {@code created_at}, keyset on {@code (created_at, id)}.
 * Reads only through the tenant-routed {@link EntityManager}, one read-only transaction per call.
 */
@Component
public class StoresExportDatasetProvider implements ExportDatasetProvider {

    static final String KEY = "stores";

    private static final List<ExportColumn> COLUMNS = List.of(
            new ExportColumn("id", "Identifiant", ExportColumnType.TEXT),
            new ExportColumn("name", "Nom", ExportColumnType.TEXT),
            new ExportColumn("type", "Type", ExportColumnType.TEXT),
            new ExportColumn("address", "Adresse", ExportColumnType.TEXT),
            new ExportColumn("phone", "Téléphone", ExportColumnType.TEXT),
            new ExportColumn("active", "Active", ExportColumnType.BOOLEAN),
            new ExportColumn("createdAt", "Créée le", ExportColumnType.DATETIME));

    private final EntityManager em;

    public StoresExportDatasetProvider(EntityManager em) {
        this.em = em;
    }

    @Override
    public String key() {
        return KEY;
    }

    @Override
    public String label() {
        return "Boutiques";
    }

    @Override
    public List<ExportColumn> columns() {
        return COLUMNS;
    }

    @Override
    @Transactional(readOnly = true)
    public long count(ExportFilter filter) {
        Map<String, Object> params = new LinkedHashMap<>();
        String where = where(filter, params);
        Query q = em.createNativeQuery("SELECT COUNT(*) FROM stores" + where);
        params.forEach(q::setParameter);
        return ((Number) q.getSingleResult()).longValue();
    }

    @Override
    @Transactional(readOnly = true)
    @SuppressWarnings("unchecked")
    public ExportPage readPage(ExportFilter filter, String cursor, int limit) {
        if (limit < 1) throw new IllegalArgumentException("limit must be >= 1");
        Map<String, Object> params = new LinkedHashMap<>();
        StringBuilder where = new StringBuilder(where(filter, params));
        if (cursor != null) {
            Position after = Position.decode(cursor);
            where.append(where.isEmpty() ? " WHERE " : " AND ")
                    .append("(created_at, id) > (:afterCreatedAt, CAST(:afterId AS uuid))");
            params.put("afterCreatedAt", Timestamp.from(after.createdAt()));
            params.put("afterId", after.id().toString());
        }
        Query q = em.createNativeQuery("SELECT id, name, type, address, phone, is_active, created_at FROM stores"
                + where + " ORDER BY created_at, id");
        params.forEach(q::setParameter);
        q.setMaxResults(limit + 1); // one extra row tells whether another page exists
        List<Object[]> raw = q.getResultList();

        boolean more = raw.size() > limit;
        List<Object[]> pageRows = more ? raw.subList(0, limit) : raw;
        List<List<Object>> rows = new ArrayList<>(pageRows.size());
        for (Object[] r : pageRows) {
            rows.add(Arrays.asList(str(r[0]), str(r[1]), str(r[2]), str(r[3]), str(r[4]),
                    (Boolean) r[5], toInstant(r[6])));
        }
        String next = null;
        if (more) {
            Object[] last = pageRows.get(pageRows.size() - 1);
            next = new Position(toInstant(last[6]), UUID.fromString(str(last[0]))).encode();
        }
        return new ExportPage(rows, next);
    }

    private static String where(ExportFilter filter, Map<String, Object> params) {
        List<String> clauses = new ArrayList<>();
        if (filter != null && filter.storeId() != null) {
            clauses.add("id = CAST(:storeId AS uuid)");
            params.put("storeId", filter.storeId().toString());
        }
        if (filter != null && filter.from() != null) {
            clauses.add("created_at >= :from");
            params.put("from", Timestamp.from(filter.from()));
        }
        if (filter != null && filter.to() != null) {
            clauses.add("created_at <= :to");
            params.put("to", Timestamp.from(filter.to()));
        }
        return clauses.isEmpty() ? "" : " WHERE " + String.join(" AND ", clauses);
    }

    private static String str(Object o) {
        return o != null ? o.toString() : null;
    }

    static Instant toInstant(Object value) {
        if (value == null) return null;
        if (value instanceof Instant i) return i;
        if (value instanceof Timestamp t) return t.toInstant();
        if (value instanceof OffsetDateTime o) return o.toInstant();
        if (value instanceof ZonedDateTime z) return z.toInstant();
        if (value instanceof LocalDateTime l) return l.toInstant(ZoneOffset.UTC);
        if (value instanceof java.util.Date d) return d.toInstant();
        throw new IllegalArgumentException("Unsupported timestamp value: " + value.getClass());
    }

    /** Keyset position, encoded as Base64URL of {@code "<ISO instant>|<uuid>"}. */
    record Position(Instant createdAt, UUID id) {

        String encode() {
            return Base64.getUrlEncoder().withoutPadding()
                    .encodeToString((createdAt + "|" + id).getBytes(StandardCharsets.UTF_8));
        }

        static Position decode(String cursor) {
            try {
                String raw = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
                int sep = raw.indexOf('|');
                return new Position(Instant.parse(raw.substring(0, sep)), UUID.fromString(raw.substring(sep + 1)));
            } catch (RuntimeException e) {
                throw new IllegalArgumentException("Malformed export cursor", e);
            }
        }
    }
}
