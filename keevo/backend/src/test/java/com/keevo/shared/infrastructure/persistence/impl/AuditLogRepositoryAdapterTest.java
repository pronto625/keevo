package com.keevo.shared.infrastructure.persistence.impl;

import com.keevo.shared.application.port.AuditPort;
import com.keevo.shared.infrastructure.persistence.entity.AuditLogJpaEntity;
import com.keevo.shared.infrastructure.persistence.jpa.AuditLogSpringRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * AuditLogRepositoryAdapterTest — TDD tests for AuditLogRepositoryAdapter.
 *
 * <p>record() still uses AuditLogSpringRepository.save() — mocked directly.
 * Query methods (findBy*, findAll) use EntityManager.createNativeQuery() with
 * a LEFT JOIN public.users — results are Object[] rows with indices:
 *   [0]=id, [1]=entityType, [2]=entityId, [3]=action,
 *   [4]=valueBefore, [5]=valueAfter, [6]=userId, [7]=occurredAt, [8]=actorPhone
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AuditLogRepositoryAdapter")
class AuditLogRepositoryAdapterTest {

    @Mock
    AuditLogSpringRepository auditLogSpringRepository;

    @Mock
    EntityManager entityManager;

    @Mock
    Query nativeQuery;

    AuditLogRepositoryAdapter adapter;

    @BeforeEach
    void setUp() {
        adapter = new AuditLogRepositoryAdapter(auditLogSpringRepository, entityManager);
        // Default stub: createNativeQuery returns chainable mock
        lenient().when(entityManager.createNativeQuery(anyString())).thenReturn(nativeQuery);
        lenient().when(nativeQuery.setParameter(anyString(), any())).thenReturn(nativeQuery);
        lenient().when(nativeQuery.setFirstResult(anyInt())).thenReturn(nativeQuery);
        lenient().when(nativeQuery.setMaxResults(anyInt())).thenReturn(nativeQuery);
    }

    // ── record() ──────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("record() calls repository.save() with all fields populated")
    void record_callsSave_withAllFields() {
        UUID actorId   = UUID.randomUUID();
        UUID entityId  = UUID.randomUUID();

        adapter.record(actorId, "kv_abc123", "USER_REGISTERED", "User", entityId,
                null, "{\"tenantCode\":\"KV-ABC\"}");

        ArgumentCaptor<AuditLogJpaEntity> captor = ArgumentCaptor.forClass(AuditLogJpaEntity.class);
        verify(auditLogSpringRepository).save(captor.capture());

        AuditLogJpaEntity saved = captor.getValue();
        assertThat(saved.getUserId()).isEqualTo(actorId);
        assertThat(saved.getEntityType()).isEqualTo("User");
        assertThat(saved.getEntityId()).isEqualTo(entityId);
        assertThat(saved.getAction()).isEqualTo("USER_REGISTERED");
        assertThat(saved.getValueBefore()).isNull();
        assertThat(saved.getValueAfter()).isEqualTo("{\"tenantCode\":\"KV-ABC\"}");
        assertThat(saved.getOccurredAt()).isNotNull();
    }

    @Test
    @DisplayName("record() also persists with non-null valueBefore (update events)")
    void record_callsSave_withValueBefore() {
        UUID actorId  = UUID.randomUUID();
        UUID entityId = UUID.randomUUID();

        adapter.record(actorId, "kv_abc123", "PRODUCT_PRICE_UPDATED", "Product", entityId,
                "{\"price\":100}", "{\"price\":150}");

        ArgumentCaptor<AuditLogJpaEntity> captor = ArgumentCaptor.forClass(AuditLogJpaEntity.class);
        verify(auditLogSpringRepository).save(captor.capture());

        AuditLogJpaEntity saved = captor.getValue();
        assertThat(saved.getValueBefore()).isEqualTo("{\"price\":100}");
        assertThat(saved.getValueAfter()).isEqualTo("{\"price\":150}");
    }

    // ── findByEntityTypeAndEntityId() ─────────────────────────────────────────────

    @Test
    @DisplayName("findByEntityTypeAndEntityId() executes native query and maps Object[] rows")
    void findByEntityTypeAndEntityId_mapsCorrectly() {
        UUID entityId = UUID.randomUUID();
        UUID userId   = UUID.randomUUID();
        Instant now   = Instant.now();
        String phone  = "+237690000001";

        when(nativeQuery.getResultList())
                .thenReturn(rows(buildRow(UUID.randomUUID(), "User", entityId,
                        "USER_REGISTERED", null, "{}", userId, now, phone)));

        AuditPort.AuditPage page = adapter.findByEntityTypeAndEntityId("User", entityId, 0, 20);

        verify(nativeQuery).setParameter("entityType", "User");
        verify(nativeQuery).setParameter("entityId", entityId.toString());
        verify(nativeQuery).setFirstResult(0);
        verify(nativeQuery).setMaxResults(21);

        assertThat(page.entries()).hasSize(1);
        assertThat(page.hasMore()).isFalse();
        AuditPort.AuditEntryRecord record = page.entries().get(0);
        assertThat(record.entityType()).isEqualTo("User");
        assertThat(record.entityId()).isEqualTo(entityId);
        assertThat(record.action()).isEqualTo("USER_REGISTERED");
        assertThat(record.userId()).isEqualTo(userId);
        assertThat(record.valueBefore()).isNull();
        assertThat(record.valueAfter()).isEqualTo("{}");
        assertThat(record.actorPhone()).isEqualTo(phone);
        assertThat(record.occurredAt()).isEqualTo(now);
    }

    @Test
    @DisplayName("findByEntityTypeAndEntityId() returns empty list when no entries found")
    void findByEntityTypeAndEntityId_returnsEmptyList() {
        when(nativeQuery.getResultList()).thenReturn(List.of());

        AuditPort.AuditPage page =
                adapter.findByEntityTypeAndEntityId("Product", UUID.randomUUID(), 0, 20);

        assertThat(page.entries()).isEmpty();
        assertThat(page.hasMore()).isFalse();
    }

    // ── findByEntityType() ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("findByEntityType() executes native query with entityType filter and maps results")
    void findByEntityType_mapsCorrectly() {
        UUID userId   = UUID.randomUUID();
        UUID entityId = UUID.randomUUID();
        Instant now   = Instant.now();

        when(nativeQuery.getResultList())
                .thenReturn(rows(buildRow(UUID.randomUUID(), "User", entityId,
                        "USER_REGISTERED", null, "{}", userId, now, null)));

        AuditPort.AuditPage page = adapter.findByEntityType("User", 0, 20);

        verify(nativeQuery).setParameter("entityType", "User");
        assertThat(page.entries()).hasSize(1);
        assertThat(page.entries().get(0).entityType()).isEqualTo("User");
        assertThat(page.entries().get(0).actorPhone()).isNull(); // LEFT JOIN may return null
        assertThat(page.hasMore()).isFalse();
    }

    // ── findAll() ─────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("findAll() executes native query with no filter and returns full tenant log")
    void findAll_returnsFullTenantLog() {
        UUID userId1  = UUID.randomUUID();
        UUID userId2  = UUID.randomUUID();
        Instant now   = Instant.now();

        when(nativeQuery.getResultList())
                .thenReturn(rows(
                        buildRow(UUID.randomUUID(), "User",    UUID.randomUUID(), "USER_REGISTERED",  null, "{}", userId1, now, "+237690000001"),
                        buildRow(UUID.randomUUID(), "Product", UUID.randomUUID(), "PRODUCT_CREATED", null, "{}", userId2, now, "+237690000002")
                ));

        AuditPort.AuditPage page = adapter.findAll(0, 20);

        assertThat(page.entries()).hasSize(2);
        assertThat(page.hasMore()).isFalse();
        assertThat(page.entries().get(0).actorPhone()).isEqualTo("+237690000001");
        assertThat(page.entries().get(1).actorPhone()).isEqualTo("+237690000002");
    }

    // ── Helpers ─────────────────────────────────────────────────────────────────────

    /**
     * Build an Object[] row matching the SELECT_WITH_PHONE native query column order:
     * [0]=id::text, [1]=entity_type, [2]=entity_id::text, [3]=action,
     * [4]=value_before, [5]=value_after, [6]=user_id::text, [7]=occurred_at, [8]=phone_number
     */
    private Object[] buildRow(UUID id, String entityType, UUID entityId, String action,
                              String valueBefore, String valueAfter, UUID userId,
                              Instant occurredAt, String actorPhone) {
        return new Object[]{
                id.toString(),
                entityType,
                entityId.toString(),
                action,
                valueBefore,
                valueAfter,
                userId.toString(),
                Timestamp.from(occurredAt),
                actorPhone
        };
    }

    /** Builds a List<Object[]> without null-spreading that List.of(Object[]) would cause. */
    private List<Object[]> rows(Object[]... rows) {
        List<Object[]> list = new ArrayList<>();
        for (Object[] row : rows) list.add(row);
        return list;
    }

    private AuditLogJpaEntity buildEntity(UUID userId, String entityType, UUID entityId,
                                          String action, String valueBefore, String valueAfter,
                                          Instant occurredAt) {
        return new AuditLogJpaEntity(UUID.randomUUID(), userId, entityType, entityId,
                action, valueBefore, valueAfter, occurredAt);
    }
}
