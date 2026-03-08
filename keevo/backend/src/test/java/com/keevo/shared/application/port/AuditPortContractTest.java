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
            public List<AuditEntryRecord> findByEntityTypeAndEntityId(String entityType, UUID entityId) {
                return List.of();
            }
            @Override
            public List<AuditEntryRecord> findByEntityType(String entityType) {
                return List.of();
            }
            @Override
            public List<AuditEntryRecord> findAll() {
                return List.of();
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
            public List<AuditEntryRecord> findByEntityTypeAndEntityId(String entityType, UUID entityId) {
                return List.of();
            }
            @Override
            public List<AuditEntryRecord> findByEntityType(String entityType) {
                return List.of();
            }
            @Override
            public List<AuditEntryRecord> findAll() {
                return List.of();
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
}
