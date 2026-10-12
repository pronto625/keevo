package com.keevo.commerce.sale.application.service;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("SaleAuditSnapshot (v1s-18-6) — no silent [] on serialization failure")
class SaleAuditSnapshotTest {

    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();
    private final Logger logger = (Logger) LoggerFactory.getLogger(SaleAuditSnapshot.class);

    @BeforeEach
    void attach() {
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void detach() {
        logger.detachAppender(appender);
    }

    @Test
    @DisplayName("serializes items to JSON")
    void serializes() {
        String json = SaleAuditSnapshot.serialize(new ObjectMapper(),
                List.of(orderedItem()), UUID.randomUUID());

        assertThat(json).isEqualTo("[{\"productId\":\"p1\",\"quantity\":2}]");
        assertThat(appender.list).isEmpty();
    }

    @Test
    @DisplayName("Jackson failure → ERROR log with saleId + marker snapshot, never []")
    void failure_logsErrorAndReturnsMarker() throws Exception {
        ObjectMapper failing = mock(ObjectMapper.class);
        when(failing.writeValueAsString(any())).thenThrow(new JsonProcessingException("boom") {});
        UUID saleId = UUID.randomUUID();

        String json = SaleAuditSnapshot.serialize(failing, List.of(), saleId);

        assertThat(json).isEqualTo(SaleAuditSnapshot.SNAPSHOT_UNAVAILABLE).isNotEqualTo("[]");
        assertThat(appender.list).singleElement().satisfies(e -> {
            assertThat(e.getLevel()).isEqualTo(Level.ERROR);
            assertThat(e.getFormattedMessage())
                    .contains("AUDIT_SNAPSHOT_SERIALIZATION_FAILED")
                    .contains(saleId.toString());
        });
    }

    /** Insertion-ordered item: Map.of has no iteration order, so the JSON key order would be random. */
    private static Map<String, Object> orderedItem() {
        Map<String, Object> item = new java.util.LinkedHashMap<>();
        item.put("productId", "p1");
        item.put("quantity", 2);
        return item;
    }
}
