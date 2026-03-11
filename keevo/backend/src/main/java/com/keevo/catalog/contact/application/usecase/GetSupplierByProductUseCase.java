package com.keevo.catalog.contact.application.usecase;

import com.keevo.catalog.contact.domain.entity.Supplier;
import com.keevo.catalog.contact.domain.port.out.SupplierRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

/**
 * GetSupplierByProductUseCase — retrieve the supplier linked to a given product (Story 2.5, AC5).
 *
 * <p>Used by GET /api/v1/products/{productId}/supplier — displays the supplier
 * on the product detail page with a tap-to-call shortcut.
 */
@Service
public class GetSupplierByProductUseCase {

    private final SupplierRepository supplierRepository;

    public GetSupplierByProductUseCase(SupplierRepository supplierRepository) {
        this.supplierRepository = supplierRepository;
    }

    @Transactional(readOnly = true)
    public Optional<Supplier> execute(UUID productId) {
        return supplierRepository.findByProductId(productId);
    }
}
