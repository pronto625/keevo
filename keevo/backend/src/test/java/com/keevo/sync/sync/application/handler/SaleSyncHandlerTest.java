package com.keevo.sync.sync.application.handler;

import com.keevo.commerce.sale.domain.port.in.RecordSaleUseCase;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import com.keevo.sync.sync.domain.model.SyncOperation;
import com.keevo.sync.sync.domain.model.SyncOperationStatus;
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

    private List<Map<String, Object>> validItems() {
        return List.of(Map.of(
                "productId", UUID.randomUUID().toString(),
                "productName", "Widget",
                "catalogueUnitPrice", 1000,
                "appliedUnitPrice", 1000,
                "quantity", 2));
    }
}
