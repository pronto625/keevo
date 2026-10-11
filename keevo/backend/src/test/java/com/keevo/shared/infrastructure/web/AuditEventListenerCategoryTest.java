package com.keevo.shared.infrastructure.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.keevo.catalog.category.domain.event.CategoryActiveChangedEvent;
import com.keevo.catalog.category.domain.event.CategoryCreatedEvent;
import com.keevo.catalog.category.domain.event.CategoryRenamedEvent;
import com.keevo.shared.application.port.AuditPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

/**
 * Tests for Category event handlers in AuditEventListener (v1s-14-15).
 *
 * <p>All events come from AUTHENTICATED endpoints (REST or /sync) → TenantContext already set.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AuditEventListener — Category Events (v1s-14-15)")
class AuditEventListenerCategoryTest {

    @Mock AuditPort auditPort;

    AuditEventListener listener;
    ObjectMapper objectMapper = new ObjectMapper();

    private static final UUID CATEGORY_ID = UUID.randomUUID();
    private static final UUID ACTOR_ID    = UUID.randomUUID();
    private static final String TENANT    = "kv_abc123";
    private static final Instant NOW      = Instant.now();

    @BeforeEach
    void setUp() {
        listener = new AuditEventListener(auditPort, objectMapper, null);
    }

    @Test
    @DisplayName("on(CategoryCreatedEvent) records CATEGORY_CREATED (root category, null parent)")
    void on_categoryCreated_records_audit() {
        listener.on(new CategoryCreatedEvent(CATEGORY_ID, "Boissons", null, ACTOR_ID, TENANT, NOW));

        var after = ArgumentCaptor.forClass(String.class);
        verify(auditPort).record(eq(ACTOR_ID), eq(TENANT), eq("CATEGORY_CREATED"), eq("Category"),
                eq(CATEGORY_ID), eq(null), after.capture());
        assertThat(after.getValue()).contains("\"name\":\"Boissons\"");
    }

    @Test
    @DisplayName("on(CategoryCreatedEvent) with a parent records parentId in valueAfter")
    void on_categoryCreated_withParent_recordsParentId() {
        UUID parentId = UUID.randomUUID();
        listener.on(new CategoryCreatedEvent(CATEGORY_ID, "Sodas", parentId, ACTOR_ID, TENANT, NOW));

        var after = ArgumentCaptor.forClass(String.class);
        verify(auditPort).record(eq(ACTOR_ID), eq(TENANT), eq("CATEGORY_CREATED"), eq("Category"),
                eq(CATEGORY_ID), eq(null), after.capture());
        assertThat(after.getValue())
                .contains("\"name\":\"Sodas\"")
                .contains("\"parentId\":\"" + parentId + "\"");
    }

    @Test
    @DisplayName("on(CategoryActiveChangedEvent active=false) records CATEGORY_DEACTIVATED")
    void on_categoryDeactivated_records_audit() {
        listener.on(new CategoryActiveChangedEvent(CATEGORY_ID, "Boissons", false, ACTOR_ID, TENANT, NOW));

        var before = ArgumentCaptor.forClass(String.class);
        var after = ArgumentCaptor.forClass(String.class);
        verify(auditPort).record(eq(ACTOR_ID), eq(TENANT), eq("CATEGORY_DEACTIVATED"), eq("Category"),
                eq(CATEGORY_ID), before.capture(), after.capture());
        assertThat(before.getValue()).contains("\"active\":true");
        assertThat(after.getValue()).contains("\"active\":false").contains("\"name\":\"Boissons\"");
    }

    @Test
    @DisplayName("on(CategoryActiveChangedEvent active=true) records CATEGORY_ACTIVATED")
    void on_categoryActivated_records_audit() {
        listener.on(new CategoryActiveChangedEvent(CATEGORY_ID, "Boissons", true, ACTOR_ID, TENANT, NOW));

        var before = ArgumentCaptor.forClass(String.class);
        var after = ArgumentCaptor.forClass(String.class);
        verify(auditPort).record(eq(ACTOR_ID), eq(TENANT), eq("CATEGORY_ACTIVATED"), eq("Category"),
                eq(CATEGORY_ID), before.capture(), after.capture());
        assertThat(before.getValue()).contains("\"active\":false");
        assertThat(after.getValue()).contains("\"active\":true").contains("\"name\":\"Boissons\"");
    }

    @Test
    @DisplayName("on(CategoryRenamedEvent) records CATEGORY_RENAMED with old/new names")
    void on_categoryRenamed_records_audit() {
        listener.on(new CategoryRenamedEvent(CATEGORY_ID, "Ancien", "Nouveau", ACTOR_ID, TENANT, NOW));

        var before = ArgumentCaptor.forClass(String.class);
        var after = ArgumentCaptor.forClass(String.class);
        verify(auditPort).record(eq(ACTOR_ID), eq(TENANT), eq("CATEGORY_RENAMED"), eq("Category"),
                eq(CATEGORY_ID), before.capture(), after.capture());
        assertThat(before.getValue()).contains("Ancien");
        assertThat(after.getValue()).contains("Nouveau");
    }
}
