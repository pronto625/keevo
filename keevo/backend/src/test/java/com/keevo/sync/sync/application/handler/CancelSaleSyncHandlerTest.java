package com.keevo.sync.sync.application.handler;

import com.keevo.commerce.sale.domain.model.PaymentMode;
import com.keevo.commerce.sale.domain.model.Sale;
import com.keevo.commerce.sale.domain.model.SaleItem;
import com.keevo.commerce.sale.domain.model.SaleStatus;
import com.keevo.commerce.sale.domain.port.in.CancelPendingSaleUseCase;
import com.keevo.commerce.sale.domain.port.out.SaleRepository;
import com.keevo.sync.sync.domain.model.SyncOperation;
import com.keevo.sync.sync.domain.model.SyncOperationStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * CancelSaleSyncHandlerTest — Story v1s-13-5 AC5. No dedicated test existed for this
 * handler before this story.
 */
@ExtendWith(MockitoExtension.class)
class CancelSaleSyncHandlerTest {

    @Mock private CancelPendingSaleUseCase cancelPendingSaleUseCase;
    @Mock private SaleRepository saleRepository;

    private CancelSaleSyncHandler handler;

    private static final UUID SALE_ID = UUID.randomUUID();
    private static final UUID ACTOR_ID = UUID.randomUUID();
    private static final UUID STORE_ID = UUID.randomUUID();
    private static final String TENANT_ID = "kv_abc123";

    @BeforeEach
    void setUp() {
        handler = new CancelSaleSyncHandler(cancelPendingSaleUseCase, saleRepository);
    }

    private Sale saleWithStatus(SaleStatus status) {
        var item = new SaleItem(UUID.randomUUID(), SALE_ID, UUID.randomUUID(), null,
                "Produit", 1000, 1000, 1);
        return new Sale(SALE_ID, STORE_ID, ACTOR_ID, null,
                PaymentMode.CASH, 1000, 0, status, Instant.now(), Instant.now(), List.of(item));
    }

    @Test
    void apply_pendingSale_succeeds() {
        when(saleRepository.findById(SALE_ID)).thenReturn(Optional.of(saleWithStatus(SaleStatus.PENDING_VALIDATION)));

        var op = new SyncOperation("op-1", "CANCEL_SALE", SALE_ID.toString(),
                Map.of("saleId", SALE_ID.toString(), "justification", "Client a changé d'avis"),
                Instant.now());

        var result = handler.handle(op, ACTOR_ID, TENANT_ID);

        assertThat(result.status()).isEqualTo(SyncOperationStatus.APPLIED);
        verify(cancelPendingSaleUseCase).cancelPendingSale(any());
    }

    @Test
    void apply_completedSale_rejectedForbidden() {
        when(saleRepository.findById(SALE_ID)).thenReturn(Optional.of(saleWithStatus(SaleStatus.COMPLETED)));

        var op = new SyncOperation("op-2", "CANCEL_SALE", SALE_ID.toString(),
                Map.of("saleId", SALE_ID.toString(), "justification", "Tentative offline sur vente complétée"),
                Instant.now());

        var result = handler.handle(op, ACTOR_ID, TENANT_ID);

        assertThat(result.status()).isEqualTo(SyncOperationStatus.REJECTED);
        verify(cancelPendingSaleUseCase, never()).cancelPendingSale(any());
    }
}
