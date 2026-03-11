package com.keevo.shared.infrastructure.persistence.entity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for ClientJpaEntity field mapping (Story 2.5).
 * Pure POJO test — no Spring context required.
 */
@DisplayName("ClientJpaEntity field mapping")
class ClientJpaEntityMappingTest {

    @Test
    @DisplayName("all-args constructor maps every field correctly")
    void should_map_all_fields_via_constructor() {
        UUID id        = UUID.randomUUID();
        Instant now    = Instant.now();

        ClientJpaEntity entity = new ClientJpaEntity(
                id, "Alice", "+22670000001",
                "alice@example.com", "VIP", false, now, now);

        assertEquals(id,                  entity.getId());
        assertEquals("Alice",             entity.getName());
        assertEquals("+22670000001",      entity.getPhone());
        assertEquals("alice@example.com", entity.getEmail());
        assertEquals("VIP",               entity.getNotes());
        assertFalse(entity.getArchived());
        assertEquals(now, entity.getCreatedAt());
        assertEquals(now, entity.getUpdatedAt());
    }

    @Test
    @DisplayName("default no-arg constructor produces non-crashing instance")
    void should_construct_with_no_args() {
        ClientJpaEntity entity = new ClientJpaEntity();
        assertNotNull(entity);
        assertFalse(entity.getArchived());
    }

    @Test
    @DisplayName("setters mutate the entity fields correctly")
    void should_apply_setters() {
        UUID id = UUID.randomUUID();
        ClientJpaEntity entity = new ClientJpaEntity();
        entity.setId(id);
        entity.setName("Bob");
        entity.setPhone("+22670000002");
        entity.setEmail("bob@example.com");
        entity.setNotes("Regular");
        entity.setArchived(true);
        Instant now = Instant.now();
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);

        assertEquals(id,                entity.getId());
        assertEquals("Bob",             entity.getName());
        assertEquals("+22670000002",    entity.getPhone());
        assertEquals("bob@example.com", entity.getEmail());
        assertEquals("Regular",         entity.getNotes());
        assertTrue(entity.getArchived());
    }

    @Test
    @DisplayName("equals based on id — two entities with same id are equal")
    void should_be_equal_when_same_id() {
        UUID id = UUID.randomUUID();
        Instant now = Instant.now();
        ClientJpaEntity a = new ClientJpaEntity(id, "A", "+22670000001", null, null, false, now, now);
        ClientJpaEntity b = new ClientJpaEntity(id, "B", "+22670000002", null, null, false, now, now);
        assertEquals(a, b);
    }

    @Test
    @DisplayName("null notes is retained (nullable column)")
    void should_allow_null_notes() {
        Instant now = Instant.now();
        ClientJpaEntity entity = new ClientJpaEntity(
                UUID.randomUUID(), "X", "+22670000001", null, null, false, now, now);
        assertNull(entity.getNotes());
        assertNull(entity.getEmail());
    }
}
