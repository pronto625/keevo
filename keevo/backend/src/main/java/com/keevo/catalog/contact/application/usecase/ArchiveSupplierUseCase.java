package com.keevo.catalog.contact.application.usecase;

import com.keevo.catalog.contact.domain.event.SupplierArchivedEvent;
import com.keevo.catalog.contact.domain.port.out.SupplierRepository;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.UUID;

/**
 * ArchiveSupplierUseCase — soft-deletes a supplier and records the audit event (Story 2.5).
 */
@Service
public class ArchiveSupplierUseCase {

    private final SupplierRepository supplierRepository;
    private final ApplicationEventPublisher eventPublisher;

    public ArchiveSupplierUseCase(SupplierRepository supplierRepository,
                                  ApplicationEventPublisher eventPublisher) {
        this.supplierRepository = supplierRepository;
        this.eventPublisher     = eventPublisher;
    }

    public void execute(UUID supplierId, UUID actorId, String tenantId) {
        var supplier = supplierRepository.findById(supplierId)
                .orElseThrow(() -> new DomainException(ErrorCode.SUPPLIER_NOT_FOUND,
                        "supplierId=" + supplierId));
        supplierRepository.archive(supplierId);
        eventPublisher.publishEvent(new SupplierArchivedEvent(
                supplierId, supplier.name(), actorId, tenantId, Instant.now()));
    }
}
