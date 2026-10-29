package com.keevo.commerce.sale.adapter.in.api;

import com.keevo.commerce.api.SaleItemView;
import com.keevo.commerce.api.SalePaymentMode;
import com.keevo.commerce.api.SaleState;
import com.keevo.commerce.api.SaleView;
import com.keevo.commerce.sale.domain.model.PaymentMode;
import com.keevo.commerce.sale.domain.model.Sale;
import com.keevo.commerce.sale.domain.model.SaleItem;
import com.keevo.commerce.sale.domain.model.SaleStatus;
import com.keevo.commerce.sale.domain.port.out.SaleRepository;
import com.keevo.shared.infrastructure.persistence.TenantContext;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SaleQueryAdapterTest {

    // 12.2e: the catalog / crm / inventory / commerce API adapters require an explicit, current tenant schema
    @org.junit.jupiter.api.BeforeEach
    void bindTenantContext12e() {
        com.keevo.shared.infrastructure.persistence.TenantContext.setCurrentTenant("kv_test01");
    }

    @org.junit.jupiter.api.AfterEach
    void clearTenantContext12e() {
        com.keevo.shared.infrastructure.persistence.TenantContext.clear();
    }

    private final SaleRepository repository = mock(SaleRepository.class);
    private final SaleQueryAdapter adapter = new SaleQueryAdapter(repository);

    @Test
    void findById_mapsTheSaleAndItsItems() {
        UUID saleId = UUID.randomUUID();
        UUID store = UUID.randomUUID();
        UUID employee = UUID.randomUUID();
        UUID client = UUID.randomUUID();
        UUID itemId = UUID.randomUUID();
        UUID product = UUID.randomUUID();
        UUID variant = UUID.randomUUID();
        Instant occurred = Instant.parse("2026-10-29T09:00:00Z");
        Instant created = Instant.parse("2026-10-29T09:05:00Z");
        Sale sale = new Sale(saleId, store, employee, client, PaymentMode.MOBILE_MONEY, 5500, 500,
                SaleStatus.PENDING_VALIDATION, occurred, created,
                List.of(new SaleItem(itemId, saleId, product, variant, "Pagne", 3000, 3000, 2)));
        when(repository.findById(saleId)).thenReturn(Optional.of(sale));

        assertThat(adapter.findById(TenantContext.getCurrentTenant(), saleId)).contains(new SaleView(saleId, store, employee, client,
                SalePaymentMode.MOBILE_MONEY, SaleState.PENDING_VALIDATION, 5500, 500, occurred, created,
                List.of(new SaleItemView(itemId, product, variant, "Pagne", 3000, 3000, 2))));
    }

    @Test
    void findById_unknownSale_isEmpty() {
        UUID saleId = UUID.randomUUID();
        when(repository.findById(saleId)).thenReturn(Optional.empty());

        assertThat(adapter.findById(TenantContext.getCurrentTenant(), saleId)).isEmpty();
    }

    // 12.2e: the explicit tenantSchema must be the bound tenant — another or a missing schema fails fast
    @org.junit.jupiter.api.Test
    void tenantGuard_otherOrMissingSchemaFailsFast() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> adapter.findById("kv_other1", java.util.UUID.randomUUID()))
                .isInstanceOf(IllegalStateException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> adapter.findById(null, java.util.UUID.randomUUID()))
                .isInstanceOf(IllegalStateException.class);
        org.mockito.Mockito.verifyNoInteractions(repository);
    }
}
