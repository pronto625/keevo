package com.keevo.shared.infrastructure.web;

import com.keevo.shared.application.port.AuditPort;
import org.mockito.ArgumentMatcher;

import java.util.Objects;

import static org.mockito.ArgumentMatchers.argThat;

/**
 * Test helper (v1s-18-6): Mockito matcher over {@link AuditPort.AuditEntry}, so listener tests
 * keep asserting actor / tenant / action / entity / before / after now that the listener writes
 * through {@code record(AuditEntry)}. {@link #ANY} ignores a field; {@code null} requires null.
 */
public final class AuditEntryMatchers {

    /** Sentinel: the field is not checked. */
    public static final Object ANY = new Object();

    private AuditEntryMatchers() {
    }

    public static AuditPort.AuditEntry entry(Object actorId, Object tenantId, Object action,
                                             Object entityType, Object entityId,
                                             Object valueBefore, Object valueAfter) {
        return argThat(matcher(actorId, tenantId, action, entityType, entityId, valueBefore, valueAfter));
    }

    public static AuditPort.AuditEntry entry(Object actorId, Object tenantId, Object action,
                                             Object entityType, Object entityId, Object valueBefore) {
        return entry(actorId, tenantId, action, entityType, entityId, valueBefore, ANY);
    }

    public static AuditPort.AuditEntry entry(Object actorId, Object tenantId, Object action,
                                             Object entityType, Object entityId) {
        return entry(actorId, tenantId, action, entityType, entityId, ANY, ANY);
    }

    static ArgumentMatcher<AuditPort.AuditEntry> matcher(Object actorId, Object tenantId, Object action,
                                                         Object entityType, Object entityId,
                                                         Object valueBefore, Object valueAfter) {
        return e -> e != null
                && field(actorId, e.actorId())
                && field(tenantId, e.tenantId())
                && field(action, e.action())
                && field(entityType, e.entityType())
                && field(entityId, e.entityId())
                && field(valueBefore, e.valueBefore())
                && field(valueAfter, e.valueAfter());
    }

    private static boolean field(Object expected, Object actual) {
        return expected == ANY || Objects.equals(expected, actual);
    }
}
