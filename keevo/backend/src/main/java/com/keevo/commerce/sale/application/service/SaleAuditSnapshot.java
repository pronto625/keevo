package com.keevo.commerce.sale.application.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.UUID;

/**
 * SaleAuditSnapshot — serializes sale items for audit events (Story v1s-18-6, item CR v1s-13-5).
 *
 * <p>A serialization failure is never silent: it is logged at ERROR with the sale id
 * ({@code AUDIT_SNAPSHOT_SERIALIZATION_FAILED}, alertable) and the audit entry still gets a
 * marker snapshot {@value #SNAPSHOT_UNAVAILABLE} instead of an empty {@code []} that would
 * read as "no items".
 */
final class SaleAuditSnapshot {

    static final String SNAPSHOT_UNAVAILABLE = "{\"error\":\"SNAPSHOT_UNAVAILABLE\"}";

    private static final Logger log = LoggerFactory.getLogger(SaleAuditSnapshot.class);

    private SaleAuditSnapshot() {
    }

    static String serialize(ObjectMapper objectMapper, Object items, UUID saleId) {
        try {
            return objectMapper.writeValueAsString(items);
        } catch (JsonProcessingException | RuntimeException e) {
            log.error("AUDIT_SNAPSHOT_SERIALIZATION_FAILED saleId={} error={}", saleId, e.getMessage(), e);
            return SNAPSHOT_UNAVAILABLE;
        }
    }
}
