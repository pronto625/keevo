package com.keevo.sync.sync.adapter.out.persistence.impl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.keevo.shared.infrastructure.persistence.TenantContext;
import com.keevo.sync.sync.domain.model.SyncErrorLogEntry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SyncErrorLogRepositoryAdapterTest {

    private static final String TENANT_SCHEMA = "kv_abc123";

    @Mock
    private JdbcTemplate jdbcTemplate;

    private SyncErrorLogRepositoryAdapter adapter;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        adapter = new SyncErrorLogRepositoryAdapter(jdbcTemplate, objectMapper);
        // qualifiedTable() resolves the schema from TenantContext (ARCH18 — raw
        // JdbcTemplate connections have no guaranteed search_path).
        TenantContext.setCurrentTenant(TENANT_SCHEMA);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    // ── save ─────────────────────────────────────────────────────────────────

    @Test
    void save_validEntry_persists() {
        var entry = new SyncErrorLogEntry(
                UUID.randomUUID(), "op-1", "CREATE_SALE", "entity-1",
                Map.of("amount", 5000), "UNKNOWN_OPERATION_TYPE",
                Instant.parse("2026-03-24T10:00:00Z"), Instant.now());

        adapter.save(entry);

        verify(jdbcTemplate).update(
                contains("INSERT INTO \"" + TENANT_SCHEMA + "\".sync_error_log"),
                eq(entry.id()),
                eq(entry.operationId()),
                eq(entry.operationType()),
                eq(entry.entityId()),
                any(String.class), // JSON payload
                eq(entry.errorReason()),
                any(Timestamp.class));
    }

    // ── findByOperationId ────────────────────────────────────────────────────

    @Test
    void findByOperationId_exists_returnsEntry() {
        var expected = new SyncErrorLogEntry(
                UUID.randomUUID(), "op-1", "CREATE_SALE", "entity-1",
                Map.of("test", true), "INTERNAL_ERROR",
                Instant.now(), Instant.now());

        when(jdbcTemplate.query(contains("WHERE operation_id"), any(RowMapper.class), eq("op-1")))
                .thenReturn(List.of(expected));

        Optional<SyncErrorLogEntry> result = adapter.findByOperationId("op-1");

        assertThat(result).isPresent();
        assertThat(result.get().operationId()).isEqualTo("op-1");
        assertThat(result.get().operationType()).isEqualTo("CREATE_SALE");
    }

    @Test
    void findByOperationId_notFound_returnsEmpty() {
        when(jdbcTemplate.query(contains("WHERE operation_id"), any(RowMapper.class), eq("op-999")))
                .thenReturn(List.of());

        assertThat(adapter.findByOperationId("op-999")).isEmpty();
    }

    // ── deleteOlderThan ──────────────────────────────────────────────────────

    @Test
    void deleteOlderThan_purgesExpired() {
        Instant cutoff = Instant.now().minusSeconds(86400 * 30);
        when(jdbcTemplate.update(
                contains("DELETE FROM \"" + TENANT_SCHEMA + "\".sync_error_log"),
                any(Timestamp.class)))
                .thenReturn(5);

        int deleted = adapter.deleteOlderThan(cutoff);

        assertThat(deleted).isEqualTo(5);
        verify(jdbcTemplate).update(
                contains("WHERE created_at <"),
                any(Timestamp.class));
    }

    // ── tenant context guard (regression — bug fixed 2026-08-12) ───────────────

    @Test
    void save_withoutTenantContext_throwsInsteadOfHittingPublicSchema() {
        // Regression: the previous unqualified "INSERT INTO sync_error_log" resolved
        // against whatever search_path the pooled connection happened to have — public
        // for a connection last used by Hibernate — causing a silent, intermittent
        // "relation sync_error_log does not exist" in production. Now it fails loudly
        // and immediately if TenantContext isn't set, instead of guessing a schema.
        TenantContext.clear();
        var entry = new SyncErrorLogEntry(
                UUID.randomUUID(), "op-1", "CREATE_SALE", "entity-1",
                Map.of("amount", 5000), "UNKNOWN_OPERATION_TYPE",
                Instant.now(), Instant.now());

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> adapter.save(entry))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(jdbcTemplate);
    }
}
