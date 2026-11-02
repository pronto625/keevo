package com.keevo.sync.sync.application.handler;

import com.keevo.inventory.api.RecordedMovement;
import com.keevo.inventory.api.StockCommandPort;
import com.keevo.inventory.api.StockQueryPort;
import com.keevo.inventory.api.StockTrackingChange;
import com.keevo.shared.application.port.sync.SyncOperation;
import com.keevo.shared.application.port.sync.SyncOperationStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/** Story 17.12 — {@code SET_PRODUCT_STOCK_TRACKING}. */
@ExtendWith(MockitoExtension.class)
class StockTrackingSyncHandlerTest {

    @Mock private StockCommandPort stockCommand;
    @Mock private StockQueryPort stockQuery;

    private StockTrackingSyncHandler handler;

    private static final String TENANT = "kv_abc123";
    private static final UUID ACTOR = UUID.randomUUID();
    private static final UUID PRODUCT = UUID.randomUUID();
    private static final UUID STORE = UUID.randomUUID();
    private static final UUID MOVEMENT = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        handler = new StockTrackingSyncHandler(stockCommand, stockQuery);
    }

    @AfterEach
    void tearDown() {
        SyncTestAuth.clear();
    }

    private static SyncOperation op(Map<String, Object> payload) {
        return new SyncOperation("op-1", "SET_PRODUCT_STOCK_TRACKING", PRODUCT.toString(), payload, Instant.now());
    }

    private static Map<String, Object> payload(Object tracked, Object quantity) {
        Map<String, Object> p = new HashMap<>();
        p.put("productId", PRODUCT.toString());
        p.put("tracked", tracked);
        if (quantity != null) {
            p.put("initialQuantity", quantity);
            p.put("storeId", STORE.toString());
            p.put("movementId", MOVEMENT.toString());
        }
        return p;
    }

    @Test
    void owner_enableWithQuantity_passesEverythingToInventory() {
        SyncTestAuth.owner();
        when(stockCommand.changeTracking(eq(TENANT), any())).thenReturn(true);

        var result = handler.handle(op(payload(true, 12)), ACTOR, TENANT);

        assertThat(result.status()).isEqualTo(SyncOperationStatus.APPLIED);
        assertThat(result.conflictData()).isNull();
        assertThat(result.serverEntityId()).isEqualTo(PRODUCT.toString());
        var captor = ArgumentCaptor.forClass(StockTrackingChange.class);
        verify(stockCommand).changeTracking(eq(TENANT), captor.capture());
        assertThat(captor.getValue()).isEqualTo(
                new StockTrackingChange(PRODUCT, true, 12, STORE, MOVEMENT, ACTOR, "OWNER"));
    }

    @Test
    void owner_disable_withoutQuantity() {
        SyncTestAuth.owner();

        assertThat(handler.handle(op(payload(false, null)), ACTOR, TENANT).status())
                .isEqualTo(SyncOperationStatus.APPLIED);
        verify(stockCommand).changeTracking(eq(TENANT),
                eq(new StockTrackingChange(PRODUCT, false, null, null, null, ACTOR, "OWNER")));
        verifyNoInteractions(stockQuery);
    }

    @Test
    void employee_isForbidden_nothingChanged() {
        SyncTestAuth.employee(STORE);

        var result = handler.handle(op(payload(false, null)), ACTOR, TENANT);

        assertThat(result.status()).isEqualTo(SyncOperationStatus.REJECTED);
        assertThat(result.reason()).isEqualTo("FORBIDDEN");
        verifyNoInteractions(stockCommand);
    }

    @Test
    void trackedNotABoolean_isRejected() {
        SyncTestAuth.owner();

        var result = handler.handle(op(payload("oui", null)), ACTOR, TENANT);

        assertThat(result.status()).isEqualTo(SyncOperationStatus.REJECTED);
        assertThat(result.reason()).startsWith("Invalid tracked");
        verifyNoInteractions(stockCommand);
    }

    @Test
    void fractionalQuantity_isRejected() {
        SyncTestAuth.owner();

        var result = handler.handle(op(payload(true, 1.5)), ACTOR, TENANT);

        assertThat(result.status()).isEqualTo(SyncOperationStatus.REJECTED);
        assertThat(result.reason()).startsWith("Invalid initialQuantity");
        verifyNoInteractions(stockCommand);
    }

    @Test
    void movementAlreadyRecorded_sameContent_isDuplicate_differentContent_isRejected() {
        SyncTestAuth.owner();
        when(stockQuery.findMovement(TENANT, MOVEMENT)).thenReturn(Optional.of(
                new RecordedMovement(MOVEMENT, PRODUCT, null, STORE, "STOCK_ENTRY", 12)));

        assertThat(handler.handle(op(payload(true, 12)), ACTOR, TENANT).status())
                .isEqualTo(SyncOperationStatus.DUPLICATE);
        var other = handler.handle(op(payload(true, 7)), ACTOR, TENANT);
        assertThat(other.status()).isEqualTo(SyncOperationStatus.REJECTED);
        assertThat(other.reason()).isEqualTo(StockAdjustSyncHandler.MOVEMENT_ID_ALREADY_USED);
        verify(stockCommand, never()).changeTracking(any(), any());
    }

    @Test
    void moduleIsInventory() {
        assertThat(handler.moduleId()).isEqualTo("inventory");
        assertThat(handler.supportedTypes()).containsExactly("SET_PRODUCT_STOCK_TRACKING");
    }

    @Test
    void enableWithQuantity_onAlreadyTrackedProduct_appliedWithInitialEntryIgnored() {
        SyncTestAuth.owner();
        when(stockCommand.changeTracking(eq(TENANT), any())).thenReturn(false);

        var result = handler.handle(op(payload(true, 12)), ACTOR, TENANT);

        assertThat(result.status()).isEqualTo(SyncOperationStatus.APPLIED);
        assertThat(result.conflictData()).isEqualTo(Map.of("initialEntry", "IGNORED"));
    }

    @Test
    void malformedIds_areRejectedWithAClearReason_nothingChanged() {
        SyncTestAuth.owner();
        Map<String, Object> numericProduct = payload(true, null);
        numericProduct.put("productId", 42);
        Map<String, Object> badStore = payload(true, 5);
        badStore.put("storeId", "boutique");
        Map<String, Object> badMovement = payload(true, 5);
        badMovement.put("movementId", "not-a-uuid");

        assertThat(handler.handle(op(numericProduct), ACTOR, TENANT).reason()).isEqualTo("Invalid productId: must be a UUID");
        assertThat(handler.handle(op(badStore), ACTOR, TENANT).reason()).isEqualTo("Invalid storeId: must be a UUID");
        assertThat(handler.handle(op(badMovement), ACTOR, TENANT).reason()).isEqualTo("Invalid movementId: must be a UUID");
        verifyNoInteractions(stockCommand, stockQuery);
    }
}
