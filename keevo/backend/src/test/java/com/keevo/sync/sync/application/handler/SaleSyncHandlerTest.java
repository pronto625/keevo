package com.keevo.sync.sync.application.handler;

import com.keevo.commerce.sale.domain.port.in.RecordSaleUseCase;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import com.keevo.sync.sync.domain.model.SyncOperation;
import com.keevo.sync.sync.domain.model.SyncOperationStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SaleSyncHandlerTest {

    @Mock private RecordSaleUseCase recordSaleUseCase;
    @Mock private ApplicationEventPublisher eventPublisher;

    private SaleSyncHandler handler;

    private static final UUID ACTOR_ID = UUID.randomUUID();
    private static final String TENANT_ID = "kv_abc123";

    @BeforeEach
    void setUp() {
        handler = new SaleSyncHandler(recordSaleUseCase, eventPublisher);
        SyncTestAuth.owner(); // v1s-12-10: null auth now fails closed
    }

    @AfterEach
    void tearDown() {
        SyncTestAuth.clear();
    }

    private SyncOperation saleIn(UUID storeId) {
        var saleId = UUID.randomUUID();
        return new SyncOperation("op-1", "CREATE_SALE", saleId.toString(),
                Map.of("saleId", saleId.toString(), "storeId", storeId.toString(),
                        "paymentMode", "CASH", "totalAmount", 5000,
                        "discountAmount", 0, "items", validItems()),
                Instant.now());
    }

    // Over REST an employee can only sell in the store of their token; the
    // sync push must not let them decrement another store's stock.
    @Test
    void handle_employeeSellingInAnotherStore_isForbidden() {
        SyncTestAuth.employee(UUID.randomUUID());

        var result = handler.handle(saleIn(UUID.randomUUID()), ACTOR_ID, TENANT_ID);

        assertThat(result.status()).isEqualTo(SyncOperationStatus.REJECTED);
        assertThat(result.reason()).isEqualTo("FORBIDDEN");
        verifyNoInteractions(recordSaleUseCase);
    }

    @Test
    void handle_employeeSellingInOwnStore_isApplied() {
        var store = UUID.randomUUID();
        SyncTestAuth.employee(store);

        var result = handler.handle(saleIn(store), ACTOR_ID, TENANT_ID);

        assertThat(result.status()).isEqualTo(SyncOperationStatus.APPLIED);
        verify(recordSaleUseCase).recordSale(any());
    }

    @Test
    void handle_validPayload_delegatesToRecordSaleService_returnsApplied() {
        var saleId = UUID.randomUUID();
        var storeId = UUID.randomUUID();
        var op = new SyncOperation("op-1", "CREATE_SALE", saleId.toString(),
                Map.of("saleId", saleId.toString(), "storeId", storeId.toString(),
                        "paymentMode", "CASH", "totalAmount", 5000,
                        "discountAmount", 0, "items", validItems()),
                Instant.now());

        var result = handler.handle(op, ACTOR_ID, TENANT_ID);

        assertThat(result.operationId()).isEqualTo("op-1");
        assertThat(result.status()).isEqualTo(SyncOperationStatus.APPLIED);
        verify(recordSaleUseCase).recordSale(any());
    }

    @Test
    void handle_invalidPayload_returnsRejected() {
        // Missing required fields
        var op = new SyncOperation("op-1", "CREATE_SALE", "entity-1",
                Map.of(), Instant.now());

        var result = handler.handle(op, ACTOR_ID, TENANT_ID);

        assertThat(result.status()).isEqualTo(SyncOperationStatus.REJECTED);
        verify(recordSaleUseCase, never()).recordSale(any());
    }

    @Test
    void handle_domainException_returnsRejectedWithReason() {
        var saleId = UUID.randomUUID();
        var storeId = UUID.randomUUID();
        var op = new SyncOperation("op-1", "CREATE_SALE", saleId.toString(),
                Map.of("saleId", saleId.toString(), "storeId", storeId.toString(),
                        "paymentMode", "CASH", "totalAmount", 5000,
                        "discountAmount", 0, "items", validItems()),
                Instant.now());

        doThrow(new DomainException(ErrorCode.INSUFFICIENT_STOCK, "Not enough stock"))
                .when(recordSaleUseCase).recordSale(any());

        var result = handler.handle(op, ACTOR_ID, TENANT_ID);

        assertThat(result.status()).isEqualTo(SyncOperationStatus.REJECTED);
        assertThat(result.reason()).isEqualTo("INSUFFICIENT_STOCK");
    }

    @Test
    void handle_alreadyProcessed_returnsDuplicate() {
        var saleId = UUID.randomUUID();
        var storeId = UUID.randomUUID();
        var op = new SyncOperation("op-1", "CREATE_SALE", saleId.toString(),
                Map.of("saleId", saleId.toString(), "storeId", storeId.toString(),
                        "paymentMode", "CASH", "totalAmount", 5000,
                        "discountAmount", 0, "items", validItems()),
                Instant.now());

        doThrow(new DomainException(ErrorCode.SALE_ALREADY_EXISTS))
                .when(recordSaleUseCase).recordSale(any());

        var result = handler.handle(op, ACTOR_ID, TENANT_ID);

        assertThat(result.status()).isEqualTo(SyncOperationStatus.REJECTED);
        assertThat(result.reason()).isEqualTo("SALE_ALREADY_EXISTS");
    }

    @Test
    void supportedTypes_containsCreateSale() {
        assertThat(handler.supportedTypes()).containsExactly("CREATE_SALE");
    }

    // ── Offline sales keep the time they were made, not the push time ──────

    private SyncOperation saleWith(Map<String, Object> extra, Instant clientTimestamp) {
        var saleId = UUID.randomUUID();
        var payload = new java.util.HashMap<String, Object>(Map.of(
                "saleId", saleId.toString(), "storeId", UUID.randomUUID().toString(),
                "paymentMode", "CASH", "discountAmount", 0, "items", validItems()));
        payload.putAll(extra);
        return new SyncOperation("op-1", "CREATE_SALE", saleId.toString(), payload, clientTimestamp);
    }

    private RecordSaleUseCase.RecordSaleCommand capturedCommand() {
        var captor = org.mockito.ArgumentCaptor.forClass(RecordSaleUseCase.RecordSaleCommand.class);
        verify(recordSaleUseCase).recordSale(captor.capture());
        return captor.getValue();
    }

    @Test
    void handle_payloadOccurredAt_isPassedToTheSale() {
        var soldAt = Instant.parse("2026-09-28T17:00:00Z");

        handler.handle(saleWith(Map.of("occurredAt", "2026-09-28T17:00:00Z"),
                Instant.parse("2026-09-29T08:00:00Z")), ACTOR_ID, TENANT_ID);

        assertThat(capturedCommand().occurredAt()).isEqualTo(soldAt);
    }

    @Test
    void handle_noOccurredAt_fallsBackToClientTimestamp() {
        var queuedAt = Instant.parse("2026-09-28T17:05:00Z");

        handler.handle(saleWith(Map.of(), queuedAt), ACTOR_ID, TENANT_ID);

        assertThat(capturedCommand().occurredAt()).isEqualTo(queuedAt);
    }

    @Test
    void handle_futureOccurredAt_isClampedToNow() {
        var before = Instant.now();

        handler.handle(saleWith(Map.of("occurredAt", "2099-01-01T00:00:00Z"), null), ACTOR_ID, TENANT_ID);

        var occurredAt = capturedCommand().occurredAt();
        assertThat(occurredAt).isBetween(before, Instant.now());
    }

    @Test
    void handle_unparseableOccurredAt_fallsBackToClientTimestamp() {
        var queuedAt = Instant.parse("2026-09-28T17:05:00Z");

        handler.handle(saleWith(Map.of("occurredAt", "not-a-date"), queuedAt), ACTOR_ID, TENANT_ID);

        assertThat(capturedCommand().occurredAt()).isEqualTo(queuedAt);
    }

    // An offline sale already happened: the push must apply it even if the
    // server stock is short (CONFLICT / STOCK_NEGATIVE is reported afterwards).
    @Test
    void handle_allowsNegativeStock() {
        handler.handle(saleWith(Map.of(), Instant.now()), ACTOR_ID, TENANT_ID);

        assertThat(capturedCommand().allowNegativeStock()).isTrue();
    }

    // ── v1s-12-10: the draft notification event carries whether drafts were promoted ──

    private SyncOperation draftSaleIn(UUID storeId) {
        var saleId = UUID.randomUUID();
        return new SyncOperation("op-1", "CREATE_SALE", saleId.toString(),
                Map.of("saleId", saleId.toString(), "storeId", storeId.toString(),
                        "paymentMode", "CASH", "discountAmount", 0, "items", validItems(),
                        "originalDraftProductIds", List.of(UUID.randomUUID().toString())),
                Instant.now());
    }

    private com.keevo.commerce.sale.domain.model.SaleDraftProductsUpgradedEvent publishedDraftEvent() {
        var captor = org.mockito.ArgumentCaptor.forClass(
                com.keevo.commerce.sale.domain.model.SaleDraftProductsUpgradedEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        return captor.getValue();
    }

    @Test
    void handle_ownerDraftSale_eventSaysDraftsPromoted() {
        handler.handle(draftSaleIn(UUID.randomUUID()), ACTOR_ID, TENANT_ID);

        assertThat(publishedDraftEvent().draftsPromoted()).isTrue();
    }

    @Test
    void handle_employeeDraftSale_eventSaysDraftsNotPromoted() {
        var store = UUID.randomUUID();
        SyncTestAuth.employee(store);

        handler.handle(draftSaleIn(store), ACTOR_ID, TENANT_ID);

        assertThat(publishedDraftEvent().draftsPromoted()).isFalse();
    }

    private List<Map<String, Object>> validItems() {
        return List.of(Map.of(
                "productId", UUID.randomUUID().toString(),
                "productName", "Widget",
                "catalogueUnitPrice", 1000,
                "appliedUnitPrice", 1000,
                "quantity", 2));
    }

    // ── v1s-13-8: legacy "status" key fallback for stale CREATE_SALE payloads ──

    @Test
    void handle_legacyStatusKey_recordsPendingValidation() {
        var saleId = UUID.randomUUID();
        var op = new SyncOperation("op-legacy", "CREATE_SALE", saleId.toString(),
                Map.of("saleId", saleId.toString(), "storeId", UUID.randomUUID().toString(),
                        "paymentMode", "CASH", "discountAmount", 0,
                        "status", "PENDING_VALIDATION", "items", validItems()),
                Instant.now());

        var result = handler.handle(op, ACTOR_ID, TENANT_ID);

        assertThat(result.status()).isEqualTo(SyncOperationStatus.APPLIED);
        verify(recordSaleUseCase).recordSale(argThat(c ->
                c.requestedStatus() == com.keevo.commerce.sale.domain.model.SaleStatus.PENDING_VALIDATION));
    }

    @Test
    void handle_legacyStatusKey_unknownValue_isIgnored_recordedWithNullStatus() {
        var saleId = UUID.randomUUID();
        var op = new SyncOperation("op-legacy-bad", "CREATE_SALE", saleId.toString(),
                Map.of("saleId", saleId.toString(), "storeId", UUID.randomUUID().toString(),
                        "paymentMode", "CASH", "discountAmount", 0,
                        "status", "NOPE", "items", validItems()),
                Instant.now());

        var result = handler.handle(op, ACTOR_ID, TENANT_ID);

        assertThat(result.status()).isEqualTo(SyncOperationStatus.APPLIED);
        verify(recordSaleUseCase).recordSale(argThat(c -> c.requestedStatus() == null));
    }

    @Test
    void handle_requestedStatusCancelled_isRejected() {
        var saleId = UUID.randomUUID();
        var op = new SyncOperation("op-cancelled", "CREATE_SALE", saleId.toString(),
                Map.of("saleId", saleId.toString(), "storeId", UUID.randomUUID().toString(),
                        "paymentMode", "CASH", "discountAmount", 0,
                        "requestedStatus", "CANCELLED", "items", validItems()),
                Instant.now());

        var result = handler.handle(op, ACTOR_ID, TENANT_ID);

        assertThat(result.status()).isEqualTo(SyncOperationStatus.REJECTED);
        verifyNoInteractions(recordSaleUseCase);
    }

    @Test
    void handle_itemId_isPassedToRecordSaleCommand() {
        var saleId = UUID.randomUUID();
        var itemId = UUID.randomUUID();
        var item = new java.util.HashMap<String, Object>(validItems().get(0));
        item.put("itemId", itemId.toString());
        var op = new SyncOperation("op-itemid", "CREATE_SALE", saleId.toString(),
                Map.of("saleId", saleId.toString(), "storeId", UUID.randomUUID().toString(),
                        "paymentMode", "CASH", "discountAmount", 0, "items", List.of(item)),
                Instant.now());

        var result = handler.handle(op, ACTOR_ID, TENANT_ID);

        assertThat(result.status()).isEqualTo(SyncOperationStatus.APPLIED);
        var captor = org.mockito.ArgumentCaptor.forClass(RecordSaleUseCase.RecordSaleCommand.class);
        verify(recordSaleUseCase).recordSale(captor.capture());
        assertThat(captor.getValue().items().get(0).itemId()).isEqualTo(itemId);
    }

    // ── v1s-15-9: integral guard — no silent truncation ──────────────────

    private SyncOperation saleWith(Object discount, Object unitPrice, Object quantity) {
        var saleId = UUID.randomUUID();
        var item = new java.util.HashMap<String, Object>();
        item.put("productId", UUID.randomUUID().toString());
        item.put("productName", "Savon");
        item.put("catalogueUnitPrice", 500);
        item.put("appliedUnitPrice", unitPrice);
        item.put("quantity", quantity);
        var payload = new java.util.HashMap<String, Object>();
        payload.put("saleId", saleId.toString());
        payload.put("storeId", UUID.randomUUID().toString());
        payload.put("paymentMode", "CASH");
        payload.put("discountAmount", discount);
        payload.put("items", List.of(item));
        return new SyncOperation("op-int", "CREATE_SALE", saleId.toString(), payload, Instant.now());
    }

    @Test
    void handle_fractionalQuantity_isRejectedNotTruncated() {
        var result = handler.handle(saleWith(0, 500, 1.5), ACTOR_ID, TENANT_ID);

        assertThat(result.status()).isEqualTo(SyncOperationStatus.REJECTED);
        assertThat(result.reason()).startsWith("Invalid quantity");
        verifyNoInteractions(recordSaleUseCase);
    }

    @Test
    void handle_outOfRangePrice_isRejected() {
        var result = handler.handle(saleWith(0, 3e10, 1), ACTOR_ID, TENANT_ID);

        assertThat(result.status()).isEqualTo(SyncOperationStatus.REJECTED);
        assertThat(result.reason()).startsWith("Invalid appliedUnitPrice");
        verifyNoInteractions(recordSaleUseCase);
    }

    @Test
    void handle_nonNumericDiscount_isRejected() {
        var result = handler.handle(saleWith("x", 500, 1), ACTOR_ID, TENANT_ID);

        assertThat(result.status()).isEqualTo(SyncOperationStatus.REJECTED);
        assertThat(result.reason()).startsWith("Invalid discountAmount");
        verifyNoInteractions(recordSaleUseCase);
    }

    @Test
    void handle_wholeDoubleQuantity_isAccepted() {
        var result = handler.handle(saleWith(null, 500.0, 2.0), ACTOR_ID, TENANT_ID);

        assertThat(result.status()).isEqualTo(SyncOperationStatus.APPLIED);
        assertThat(capturedCommand().items().get(0).quantity()).isEqualTo(2);
    }
}
