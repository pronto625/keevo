package com.keevo.catalog.contact.application.usecase;

import com.keevo.catalog.contact.domain.event.SupplierUnarchivedEvent;
import com.keevo.catalog.contact.domain.port.out.SupplierRepository;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.UUID;

/**
 * UnarchiveSupplierUseCase — restores an archived supplier and records the audit event; mirror of
 * {@link ArchiveSupplierUseCase}, same access rules.
 */
@Service
public class UnarchiveSupplierUseCase {

    private final SupplierRepository supplierRepository;
    private final ApplicationEventPublisher eventPublisher;

    public UnarchiveSupplierUseCase(SupplierRepository supplierRepository,
                                  ApplicationEventPublisher eventPublisher) {
        this.supplierRepository = supplierRepository;
        this.eventPublisher = eventPublisher;
    }

    public void execute(UUID supplierId, UUID actorId, String tenantId) {
        var supplier = supplierRepository.findById(supplierId)
                .orElseThrow(() -> new DomainException(ErrorCode.SUPPLIER_NOT_FOUND,
                        "supplierId=" + supplierId));
        // Replayed operation: already active, nothing to change nor to audit.
        if (!supplier.archived()) return;
        supplierRepository.unarchive(supplierId);
        eventPublisher.publishEvent(new SupplierUnarchivedEvent(
                supplierId, supplier.name(), actorId, tenantId, Instant.now()));
    }
}
