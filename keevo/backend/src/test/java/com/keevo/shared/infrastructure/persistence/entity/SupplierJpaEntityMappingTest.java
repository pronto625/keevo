package com.keevo.shared.infrastructure.persistence.entity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for SupplierJpaEntity field mapping (Story 2.5).
 * Pure POJO test — no Spring context required.
 */
@DisplayName("SupplierJpaEntity field mapping")
class SupplierJpaEntityMappingTest {

    @Test
    @DisplayName("all-args constructor maps every field correctly")
    void should_map_all_fields_via_constructor() {
        UUID id     = UUID.randomUUID();
        Instant now = Instant.now();

        SupplierJpaEntity entity = new SupplierJpaEntity(
                id, "Supplier Corp", "+33612345678",
                "info@supplier.com", false, now, now);

        assertEquals(id,                 entity.getId());
        assertEquals("Supplier Corp",    entity.getName());
        assertEquals("+33612345678",     entity.getPhone());
        assertEquals("info@supplier.com", entity.getEmail());
        assertFalse(entity.getArchived());
        assertEquals(now, entity.getCreatedAt());
        assertEquals(now, entity.getUpdatedAt());
    }

    @Test
    @DisplayName("default no-arg constructor produces non-crashing instance")
    void should_construct_with_no_args() {
        SupplierJpaEntity entity = new SupplierJpaEntity();
        assertNotNull(entity);
        assertFalse(entity.getArchived());
    }

    @Test
    @DisplayName("setters mutate the entity fields correctly")
    void should_apply_setters() {
        UUID id = UUID.randomUUID();
        SupplierJpaEntity entity = new SupplierJpaEntity();
        entity.setId(id);
        entity.setName("NewName");
        entity.setPhone("+22670000099");
        entity.setEmail("new@supplier.com");
        entity.setArchived(true);
        Instant now = Instant.now();
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);

        assertEquals(id,                entity.getId());
        assertEquals("NewName",         entity.getName());
        assertEquals("+22670000099",    entity.getPhone());
        assertEquals("new@supplier.com", entity.getEmail());
        assertTrue(entity.getArchived());
    }

    @Test
    @DisplayName("equals based on id")
    void should_be_equal_when_same_id() {
        UUID id = UUID.randomUUID();
        Instant now = Instant.now();
        SupplierJpaEntity a = new SupplierJpaEntity(id, "A", "+22670000001", null, false, now, now);
        SupplierJpaEntity b = new SupplierJpaEntity(id, "B", "+22670000002", null, false, now, now);
        assertEquals(a, b);
    }
}
