package com.keevo.inventory.counting.domain.model;

import com.keevo.shared.domain.exception.DomainException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("InventorySession — Domain entity")
class InventorySessionTest {

    private static final UUID STORE_ID = UUID.randomUUID();
    private static final UUID ACTOR_ID = UUID.randomUUID();

    @Test
    void session_shouldHoldAllFields() {
        var session = InventorySession.create(STORE_ID, InventoryScope.FULL, null, ACTOR_ID);

        assertNotNull(session.getId());
        assertEquals(STORE_ID, session.getStoreId());
        assertEquals(InventoryScope.FULL, session.getScope());
        assertNull(session.getCategoryIds());
        assertEquals(InventorySessionStatus.IN_PROGRESS, session.getStatus());
        assertEquals(ACTOR_ID, session.getStartedBy());
        assertNotNull(session.getStartedAt());
        assertNull(session.getCancelledBy());
        assertNull(session.getCancelledAt());
        assertNull(session.getCompletedAt());
        assertNotNull(session.getUpdatedAt());
    }

    @Test
    void session_whenStoreIdNull_shouldThrow() {
        assertThrows(NullPointerException.class,
                () -> InventorySession.create(null, InventoryScope.FULL, null, ACTOR_ID));
    }

    @Test
    void session_fullScope_shouldHaveNullCategoryIds() {
        var session = InventorySession.create(STORE_ID, InventoryScope.FULL,
                List.of(UUID.randomUUID()), ACTOR_ID);
        assertNull(session.getCategoryIds());
    }

    @Test
    void session_partialScope_withEmptyCategoryIds_shouldThrow() {
        assertThrows(DomainException.class,
                () -> InventorySession.create(STORE_ID, InventoryScope.PARTIAL, List.of(), ACTOR_ID));
    }

    @Test
    void session_partialScope_withNullCategoryIds_shouldThrow() {
        assertThrows(DomainException.class,
                () -> InventorySession.create(STORE_ID, InventoryScope.PARTIAL, null, ACTOR_ID));
    }

    @Test
    void session_partialScope_withCategoryIds_shouldSucceed() {
        UUID catId = UUID.randomUUID();
        var session = InventorySession.create(STORE_ID, InventoryScope.PARTIAL, List.of(catId), ACTOR_ID);
        assertEquals(List.of(catId), session.getCategoryIds());
        assertEquals(InventoryScope.PARTIAL, session.getScope());
    }

    @Test
    void session_cancel_whenInProgress_shouldSetStatusAndActorAndTimestamp() {
        var session = InventorySession.create(STORE_ID, InventoryScope.FULL, null, ACTOR_ID);
        UUID cancellerId = UUID.randomUUID();

        session.cancel(cancellerId);

        assertEquals(InventorySessionStatus.CANCELLED, session.getStatus());
        assertEquals(cancellerId, session.getCancelledBy());
        assertNotNull(session.getCancelledAt());
    }

    @Test
    void session_cancel_whenAlreadyCancelled_shouldThrowDomainException() {
        var session = InventorySession.create(STORE_ID, InventoryScope.FULL, null, ACTOR_ID);
        session.cancel(UUID.randomUUID());

        assertThrows(DomainException.class, () -> session.cancel(UUID.randomUUID()));
    }

    @Test
    void session_validate_whenInProgress_shouldSetStatusAndTimestamp() {
        var session = InventorySession.create(STORE_ID, InventoryScope.FULL, null, ACTOR_ID);

        session.validate();

        assertEquals(InventorySessionStatus.VALIDATED, session.getStatus());
        assertNotNull(session.getCompletedAt());
    }

    @Test
    void session_validate_whenCancelled_shouldThrow() {
        var session = InventorySession.create(STORE_ID, InventoryScope.FULL, null, ACTOR_ID);
        session.cancel(UUID.randomUUID());

        assertThrows(DomainException.class, session::validate);
    }
}
