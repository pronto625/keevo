package com.keevo.catalog.contact.application.usecase;

import com.keevo.catalog.contact.domain.entity.Supplier;
import com.keevo.catalog.contact.domain.port.out.SupplierRepository;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * UpdateSupplierUseCase — updates mutable fields on an existing supplier (Story 2.5).
 */
@Service
public class UpdateSupplierUseCase {

    private final SupplierRepository supplierRepository;

    public UpdateSupplierUseCase(SupplierRepository supplierRepository) {
        this.supplierRepository = supplierRepository;
    }

    public record UpdateSupplierCommand(
            UUID supplierId,
            String name,
            String phone,
            String email,
            List<UUID> productIds,
            UUID actorId
    ) {}

    public Supplier execute(UpdateSupplierCommand cmd) {
        Supplier existing = supplierRepository.findById(cmd.supplierId())
                .orElseThrow(() -> new DomainException(ErrorCode.SUPPLIER_NOT_FOUND,
                        "supplierId=" + cmd.supplierId()));

        Supplier updated = new Supplier(
                existing.id(),
                cmd.name()  != null ? cmd.name()  : existing.name(),
                cmd.phone() != null ? cmd.phone() : existing.phone(),
                cmd.email() != null ? cmd.email() : existing.email(),
                existing.archived(),
                existing.createdAt(),
                Instant.now()
        );
        Supplier saved = supplierRepository.save(updated);
        if (cmd.productIds() != null) {
            supplierRepository.linkProducts(existing.id(), cmd.productIds());
        }
        return saved;
    }
}
