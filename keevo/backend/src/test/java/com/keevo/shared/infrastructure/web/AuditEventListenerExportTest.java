package com.keevo.shared.infrastructure.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.keevo.identity.auth.domain.port.out.UserRepository;
import com.keevo.reporting.export.domain.event.DataExportedEvent;
import com.keevo.shared.application.port.AuditPort;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
@DisplayName("AuditEventListener — DATA_EXPORTED (v1s-18-1)")
class AuditEventListenerExportTest {

    @Mock AuditPort auditPort;
    @Mock UserRepository userRepository;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @AfterEach
    void reset() {
        RequestContextHolder.resetRequestAttributes();
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("DataExportedEvent on a request thread → DATA_EXPORTED / Export / exportId, format + datasets + filters + rowCount + mode, source ONLINE")
    void dataExported() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Device-Id", "phone-1");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        UUID actor = UUID.randomUUID();
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                actor, null, List.of(new SimpleGrantedAuthority("ROLE_OWNER"))));
        UUID exportId = UUID.randomUUID();
        UUID store = UUID.randomUUID();
        Instant from = Instant.parse("2026-10-01T00:00:00Z");
        Instant to = Instant.parse("2026-10-12T00:00:00Z");

        new AuditEventListener(auditPort, objectMapper, userRepository).on(new DataExportedEvent(
                exportId, actor, "kv_abc123", "CSV", List.of("stores", "products"), List.of("Boutiques", "Produits"),
                store, from, to, java.util.Map.of("action", "SALE_CREATED", "q", "riz"),
                1234, "BACKGROUND", Instant.parse("2026-10-12T12:00:00Z")));

        ArgumentCaptor<AuditPort.AuditEntry> c = ArgumentCaptor.forClass(AuditPort.AuditEntry.class);
        verify(auditPort).record(c.capture());
        AuditPort.AuditEntry e = c.getValue();
        assertThat(e.action()).isEqualTo("DATA_EXPORTED");
        assertThat(e.entityType()).isEqualTo("Export");
        assertThat(e.entityId()).isEqualTo(exportId);
        assertThat(e.actorId()).isEqualTo(actor);
        assertThat(e.tenantId()).isEqualTo("kv_abc123");
        assertThat(e.storeId()).isEqualTo(store);
        assertThat(e.entityLabel()).isEqualTo("Export CSV — Boutiques, Produits");
        assertThat(e.source()).isEqualTo("ONLINE");
        assertThat(e.deviceId()).isEqualTo("phone-1");
        assertThat(e.valueBefore()).isNull();
        JsonNode after = objectMapper.readTree(e.valueAfter());
        assertThat(after.get("format").asText()).isEqualTo("CSV");
        assertThat(after.get("datasets")).hasSize(2);
        assertThat(after.get("datasets").get(0).asText()).isEqualTo("stores");
        assertThat(after.get("storeId").asText()).isEqualTo(store.toString());
        assertThat(after.get("from").asText()).isEqualTo("2026-10-01T00:00:00Z");
        assertThat(after.get("to").asText()).isEqualTo("2026-10-12T00:00:00Z");
        assertThat(after.get("rowCount").asLong()).isEqualTo(1234);
        assertThat(after.get("mode").asText()).isEqualTo("BACKGROUND");
        assertThat(after.get("criteria").get("action").asText()).isEqualTo("SALE_CREATED"); // v1s-18-2
        assertThat(after.get("criteria").get("q").asText()).isEqualTo("riz");
        assertThat(objectMapper.readTree(e.metadataJson()).get("occurredAt").asText())
                .isEqualTo("2026-10-12T12:00:00Z");
    }

    @Test
    @DisplayName("all-stores, unbounded export → storeId / from / to null in valueAfter")
    void noFilters() throws Exception {
        UUID actor = UUID.randomUUID();
        new AuditEventListener(auditPort, objectMapper, userRepository).on(new DataExportedEvent(
                UUID.randomUUID(), actor, "kv_abc123", "XLSX", List.of("stores"), List.of(), null, null, null,
                3, "DIRECT", Instant.now()));

        ArgumentCaptor<AuditPort.AuditEntry> c = ArgumentCaptor.forClass(AuditPort.AuditEntry.class);
        verify(auditPort).record(c.capture());
        JsonNode after = objectMapper.readTree(c.getValue().valueAfter());
        assertThat(after.get("storeId").isNull()).isTrue();
        assertThat(after.get("mode").asText()).isEqualTo("DIRECT");
        assertThat(after.get("criteria")).isEmpty();
        assertThat(c.getValue().entityLabel()).as("keys as fallback").isEqualTo("Export XLSX — stores");
    }
}
