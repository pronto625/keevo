package com.keevo.inventory.transfer.adapter.in.api;

import com.keevo.inventory.api.TransferInput;
import com.keevo.inventory.transfer.domain.model.StockTransfer;
import com.keevo.inventory.transfer.domain.port.in.TransferStockCommand;
import com.keevo.inventory.transfer.domain.port.in.TransferStockUseCase;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import com.keevo.shared.infrastructure.persistence.TenantContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** Story 12.2b — the transfer port maps its input onto the transfer use case command. */
@ExtendWith(MockitoExtension.class)
class TransferCommandAdapterTest {

    // 12.2e: the catalog / crm / inventory / commerce API adapters require an explicit, current tenant schema
    @org.junit.jupiter.api.BeforeEach
    void bindTenantContext12e() {
        com.keevo.shared.infrastructure.persistence.TenantContext.setCurrentTenant("kv_test01");
    }

    @org.junit.jupiter.api.AfterEach
    void clearTenantContext12e() {
        com.keevo.shared.infrastructure.persistence.TenantContext.clear();
    }

    @Mock private TransferStockUseCase transferStockUseCase;

    private static final UUID SOURCE = UUID.randomUUID();
    private static final UUID DEST = UUID.randomUUID();
    private static final UUID PRODUCT = UUID.randomUUID();
    private static final UUID VARIANT = UUID.randomUUID();
    private static final UUID ACTOR = UUID.randomUUID();
    private static final UUID CLIENT_ID = UUID.randomUUID();

    @Test
    void transfer_mapsEveryFieldAndReturnsTheTransferId() {
        UUID transferId = UUID.randomUUID();
        var transfer = mock(StockTransfer.class);
        when(transfer.getId()).thenReturn(transferId);
        when(transferStockUseCase.execute(any())).thenReturn(transfer);

        UUID result = new TransferCommandAdapter(transferStockUseCase).transfer(TenantContext.getCurrentTenant(),
                new TransferInput(SOURCE, DEST, PRODUCT, VARIANT, 3, ACTOR, "Réassort", CLIENT_ID));

        assertThat(result).isEqualTo(transferId);
        verify(transferStockUseCase).execute(
                new TransferStockCommand(SOURCE, DEST, PRODUCT, VARIANT, 3, ACTOR, "Réassort", CLIENT_ID));
    }

    @Test
    void transfer_ruleViolation_propagates() {
        when(transferStockUseCase.execute(any()))
                .thenThrow(new DomainException(ErrorCode.INSUFFICIENT_STOCK, "Stock insuffisant"));

        assertThatThrownBy(() -> new TransferCommandAdapter(transferStockUseCase).transfer(TenantContext.getCurrentTenant(),
                new TransferInput(SOURCE, DEST, PRODUCT, null, 99, ACTOR, null, null)))
                .isInstanceOf(DomainException.class);
    }

    // 12.2e: the explicit tenantSchema must be the bound tenant — another or a missing schema fails fast
    @org.junit.jupiter.api.Test
    void tenantGuard_otherOrMissingSchemaFailsFast() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new TransferCommandAdapter(transferStockUseCase).transfer("kv_other1", new TransferInput(SOURCE, DEST, PRODUCT, VARIANT, 3, ACTOR, null, null)))
                .isInstanceOf(IllegalStateException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new TransferCommandAdapter(transferStockUseCase).transfer(null, new TransferInput(SOURCE, DEST, PRODUCT, VARIANT, 3, ACTOR, null, null)))
                .isInstanceOf(IllegalStateException.class);
        org.mockito.Mockito.verifyNoInteractions(transferStockUseCase);
    }
}
