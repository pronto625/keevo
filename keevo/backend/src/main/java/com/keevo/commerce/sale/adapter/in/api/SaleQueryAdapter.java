package com.keevo.commerce.sale.adapter.in.api;

import com.keevo.commerce.api.SaleQueryPort;
import com.keevo.commerce.api.SaleView;
import com.keevo.commerce.sale.domain.port.out.SaleRepository;
import com.keevo.shared.infrastructure.persistence.TenantContext;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;

/** Implements {@link SaleQueryPort} over the sale repository port (Story 12.2c). */
@Component
class SaleQueryAdapter implements SaleQueryPort {

    private final SaleRepository saleRepository;

    SaleQueryAdapter(SaleRepository saleRepository) {
        this.saleRepository = saleRepository;
    }

    @Override
    public Optional<SaleView> findById(String tenantSchema, UUID saleId) {
        TenantContext.requireCurrent(tenantSchema);
        return saleRepository.findById(saleId).map(SaleViews::of);
    }
}
