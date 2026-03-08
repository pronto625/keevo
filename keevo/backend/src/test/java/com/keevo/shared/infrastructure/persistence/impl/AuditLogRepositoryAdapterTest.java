package com.keevo.shared.infrastructure.persistence.impl;

import com.keevo.shared.application.port.AuditPort;
import com.keevo.shared.infrastructure.persistence.entity.AuditLogJpaEntity;
import com.keevo.shared.infrastructure.persistence.jpa.AuditLogSpringRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * AuditLogRepositoryAdapterTest — TDD tests for AuditLogRepositoryAdapter.
 *
 * <p>RED → GREEN:
 * - RED: AuditLogRepositoryAdapter, AuditLogJpaEntity, AuditLogSpringRepository don't exist yet.
 * - GREEN: After implementing those classes, all tests pass.
 *
 * <p>Verifies:
 * - record() builds entity correctly and calls repository.save()
 * - findByEntityTypeAndEntityId() delegates to Spring repo and maps results
 * - findByEntityType() filters by entityType only
 * - findAll() returns full tenant log
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AuditLogRepositoryAdapter")
class AuditLogRepositoryAdapterTest {

    @Mock
    AuditLogSpringRepository auditLogSpringRepository;

    AuditLogRepositoryAdapter adapter;

    @BeforeEach
    void setUp() {
        adapter = new AuditLogRepositoryAdapter(auditLogSpringRepository);
    }

    // ── record() ──────────────────────────────────────────────────────────────

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

    // ── findByEntityTypeAndEntityId() ─────────────────────────────────────────

    @Test
    @DisplayName("findByEntityTypeAndEntityId() delegates to Spring repo and maps to AuditEntryRecord")
    void findByEntityTypeAndEntityId_mapsCorrectly() {
        UUID entityId = UUID.randomUUID();
        UUID userId   = UUID.randomUUID();
        Instant now   = Instant.now();

        AuditLogJpaEntity entity = buildEntity(userId, "User", entityId, "USER_REGISTERED", null, "{}", now);
        when(auditLogSpringRepository.findByEntityTypeAndEntityIdOrderByOccurredAtDesc("User", entityId))
                .thenReturn(List.of(entity));

        List<AuditPort.AuditEntryRecord> results = adapter.findByEntityTypeAndEntityId("User", entityId);

        assertThat(results).hasSize(1);
        AuditPort.AuditEntryRecord record = results.get(0);
        assertThat(record.entityType()).isEqualTo("User");
        assertThat(record.entityId()).isEqualTo(entityId);
        assertThat(record.action()).isEqualTo("USER_REGISTERED");
        assertThat(record.userId()).isEqualTo(userId);
        assertThat(record.valueBefore()).isNull();
        assertThat(record.valueAfter()).isEqualTo("{}");
        assertThat(record.occurredAt()).isEqualTo(now);
    }

    @Test
    @DisplayName("findByEntityTypeAndEntityId() returns empty list when no entries found")
    void findByEntityTypeAndEntityId_returnsEmptyList() {
        UUID entityId = UUID.randomUUID();
        when(auditLogSpringRepository.findByEntityTypeAndEntityIdOrderByOccurredAtDesc("Product", entityId))
                .thenReturn(List.of());

        List<AuditPort.AuditEntryRecord> results = adapter.findByEntityTypeAndEntityId("Product", entityId);

        assertThat(results).isEmpty();
    }

    // ── findByEntityType() ────────────────────────────────────────────────────

    @Test
    @DisplayName("findByEntityType() delegates to entityType-only query and maps results")
    void findByEntityType_mapsCorrectly() {
        UUID userId   = UUID.randomUUID();
        UUID entityId = UUID.randomUUID();
        Instant now   = Instant.now();

        AuditLogJpaEntity entity = buildEntity(userId, "User", entityId, "USER_REGISTERED", null, "{}", now);
        when(auditLogSpringRepository.findByEntityTypeOrderByOccurredAtDesc("User"))
                .thenReturn(List.of(entity));

        List<AuditPort.AuditEntryRecord> results = adapter.findByEntityType("User");

        assertThat(results).hasSize(1);
        assertThat(results.get(0).entityType()).isEqualTo("User");
    }

    // ── findAll() ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("findAll() returns full tenant log from Spring repo")
    void findAll_returnsFullTenantLog() {
        UUID userId1  = UUID.randomUUID();
        UUID userId2  = UUID.randomUUID();
        Instant now   = Instant.now();

        when(auditLogSpringRepository.findAllByOrderByOccurredAtDesc())
                .thenReturn(List.of(
                        buildEntity(userId1, "User",    UUID.randomUUID(), "USER_REGISTERED",    null, "{}", now),
                        buildEntity(userId2, "Product", UUID.randomUUID(), "PRODUCT_CREATED", null, "{}", now)
                ));

        List<AuditPort.AuditEntryRecord> results = adapter.findAll();

        assertThat(results).hasSize(2);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private AuditLogJpaEntity buildEntity(UUID userId, String entityType, UUID entityId,
                                          String action, String valueBefore, String valueAfter,
                                          Instant occurredAt) {
        return new AuditLogJpaEntity(UUID.randomUUID(), userId, entityType, entityId,
                action, valueBefore, valueAfter, occurredAt);
    }
}
