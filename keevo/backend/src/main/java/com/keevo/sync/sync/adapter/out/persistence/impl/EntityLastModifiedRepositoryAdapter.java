package com.keevo.sync.sync.adapter.out.persistence.impl;

import com.keevo.sync.sync.domain.port.out.EntityLastModifiedRepository;
import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Reads {@code updated_at} with a native query in the tenant schema (the table name comes from
 * the {@link LwwEntity} enum, never from the request).
 */
@Component
public class EntityLastModifiedRepositoryAdapter implements EntityLastModifiedRepository {

    private final EntityManager em;

    public EntityLastModifiedRepositoryAdapter(EntityManager em) {
        this.em = em;
    }

    @Override
    public Optional<Instant> findUpdatedAt(LwwEntity entity, UUID id) {
        List<?> rows = em.createNativeQuery(
                        "SELECT updated_at FROM " + entity.table() + " WHERE id = :id")
                .setParameter("id", id)
                .getResultList();
        if (rows.isEmpty() || rows.get(0) == null) return Optional.empty();
        return Optional.ofNullable(toInstant(rows.get(0)));
    }

    private static Instant toInstant(Object o) {
        if (o instanceof Instant i) return i;
        if (o instanceof Timestamp t) return t.toInstant();
        if (o instanceof OffsetDateTime odt) return odt.toInstant();
        // timestamp without time zone: read in the JVM zone, like the pull cursor
        if (o instanceof LocalDateTime ldt) return ldt.atZone(ZoneId.systemDefault()).toInstant();
        return null;
    }
}
