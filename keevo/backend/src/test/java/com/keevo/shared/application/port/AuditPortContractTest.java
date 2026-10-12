package com.keevo.shared.application.port;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * AuditPortContractTest — Compilation/contract test for AuditPort.
 *
 * <p>This is a TDD RED test: it verifies that the new 7-param signature for
 * {@link AuditPort#record} and the three query methods exist.
 * It will fail to compile until AuditPort is updated accordingly.
 */
@DisplayName("AuditPort — contract / signature test")
class AuditPortContractTest {

    @Test
    @DisplayName("record() signature has 7 parameters (actorId, tenantId, action, entityType, entityId, valueBefore, valueAfter)")
    void auditPort_record_hasCorrectSevenParamSignature() {
        // Anonymous implementation verifies the full contract
        AuditPort port = new AuditPort() {
            @Override
            public void record(UUID actorId, String tenantId, String action,
                               String entityType, UUID entityId,
                               String valueBefore, String valueAfter) {
            }
            @Override
            public AuditPage findByEntityTypeAndEntityId(String entityType, UUID entityId, int page, int size) {
                return new AuditPage(List.of(), false);
            }
            @Override
            public AuditPage findByEntityType(String entityType, int page, int size) {
                return new AuditPage(List.of(), false);
            }
            @Override
            public AuditPage findAll(int page, int size) {
                return new AuditPage(List.of(), false);
            }
            @Override
            public void record(AuditEntry entry) {
            }
            @Override
            public AuditSlice search(AuditQuery query, String cursor, Integer page, int size) {
                return new AuditSlice(List.of(), false, null);
            }
            @Override
            public java.util.Optional<AuditEntryRecord> findById(UUID id) {
                return java.util.Optional.empty();
            }
        };
        assertNotNull(port, "AuditPort instance must be creatable as anonymous implementation");
    }

    @Test
    @DisplayName("AuditPort.record() accepts null valueBefore (creation events have no prior state)")
    void auditPort_record_accepts_null_valueBefore() {
        AuditPort port = new AuditPort() {
            @Override
            public void record(UUID actorId, String tenantId, String action,
                               String entityType, UUID entityId,
                               String valueBefore, String valueAfter) {
                // no-op
            }
            @Override
            public AuditPage findByEntityTypeAndEntityId(String entityType, UUID entityId, int page, int size) {
                return new AuditPage(List.of(), false);
            }
            @Override
            public AuditPage findByEntityType(String entityType, int page, int size) {
                return new AuditPage(List.of(), false);
            }
            @Override
            public AuditPage findAll(int page, int size) {
                return new AuditPage(List.of(), false);
            }
            @Override
            public void record(AuditEntry entry) {
            }
            @Override
            public AuditSlice search(AuditQuery query, String cursor, Integer page, int size) {
                return new AuditSlice(List.of(), false, null);
            }
            @Override
            public java.util.Optional<AuditEntryRecord> findById(UUID id) {
                return java.util.Optional.empty();
            }
        };
        // Should not throw — null valueBefore is valid for creation/registration events
        port.record(
                UUID.randomUUID(),
                "kv_abc123",
                "USER_REGISTERED",
                "User",
                UUID.randomUUID(),
                null,
                "{\"key\":\"value\"}"
        );
    }

    @Test
    @DisplayName("v1s-18-6: legacy 7-arg record() delegates to record(AuditEntry) with null enrichment")
    void legacyRecord_delegatesToEnrichedRecord() {
        java.util.List<AuditPort.AuditEntry> captured = new java.util.ArrayList<>();
        AuditPort port = new AuditPort() {
            @Override public void record(AuditEntry entry) { captured.add(entry); }
            @Override public AuditSlice search(AuditQuery q, String c, Integer p, int s) {
                return new AuditSlice(List.of(), false, null);
            }
            @Override public java.util.Optional<AuditEntryRecord> findById(UUID id) {
                return java.util.Optional.empty();
            }
        };
        UUID actor = UUID.randomUUID();
        port.record(actor, "kv_a", "X", "T", actor, null, "{}");

        org.assertj.core.api.Assertions.assertThat(captured).singleElement().satisfies(e -> {
            org.assertj.core.api.Assertions.assertThat(e.action()).isEqualTo("X");
            org.assertj.core.api.Assertions.assertThat(e.source()).isNull();
            org.assertj.core.api.Assertions.assertThat(e.metadataJson()).isNull();
        });
    }

    @Test
    @DisplayName("v1s-18-6: cursor encode/decode round-trips; garbage is rejected")
    void cursor_roundTrip() {
        var c = new AuditPort.AuditCursor(java.time.Instant.parse("2026-10-12T08:15:30.123456Z"), UUID.randomUUID());
        org.assertj.core.api.Assertions.assertThat(AuditPort.AuditCursor.decode(c.encode())).isEqualTo(c);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> AuditPort.AuditCursor.decode("!!"))
                .isInstanceOf(IllegalArgumentException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> AuditPort.AuditCursor.decode("bm90LWEtY3Vyc29y"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
