package com.keevo.catalog.contact.application.usecase;

import com.keevo.catalog.contact.domain.entity.Supplier;
import com.keevo.catalog.contact.domain.port.out.SupplierRepository;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

/**
 * GetSupplierProfileUseCase — retrieves a supplier and the list of products it supplies (Story 2.5).
 */
@Service
public class GetSupplierProfileUseCase {

    private final SupplierRepository supplierRepository;

    public GetSupplierProfileUseCase(SupplierRepository supplierRepository) {
        this.supplierRepository = supplierRepository;
    }

    public record SupplierProfileResult(Supplier supplier, List<UUID> productIds) {}

    public SupplierProfileResult execute(UUID supplierId) {
        Supplier supplier = supplierRepository.findById(supplierId)
                .orElseThrow(() -> new DomainException(ErrorCode.SUPPLIER_NOT_FOUND,
                        "supplierId=" + supplierId));
        List<UUID> productIds = supplierRepository.findProductIdsBySupplier(supplierId);
        return new SupplierProfileResult(supplier, productIds);
    }
}
