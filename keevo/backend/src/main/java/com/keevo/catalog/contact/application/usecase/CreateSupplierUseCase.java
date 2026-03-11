package com.keevo.catalog.contact.application.usecase;

import com.keevo.catalog.contact.domain.entity.Supplier;
import com.keevo.catalog.contact.domain.event.SupplierCreatedEvent;
import com.keevo.catalog.contact.domain.port.out.SupplierRepository;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * CreateSupplierUseCase — persists a new supplier and fires SupplierCreatedEvent (Story 2.5).
 */
@Service
public class CreateSupplierUseCase {

    private final SupplierRepository supplierRepository;
    private final ApplicationEventPublisher eventPublisher;

    public CreateSupplierUseCase(SupplierRepository supplierRepository,
                                 ApplicationEventPublisher eventPublisher) {
        this.supplierRepository = supplierRepository;
        this.eventPublisher     = eventPublisher;
    }

    public record CreateSupplierCommand(
            String name,
            String phone,
            String email,
            List<UUID> productIds,
            UUID actorId,
            String tenantId
    ) {}

    public Supplier execute(CreateSupplierCommand cmd) {
        Supplier supplier = new Supplier(
                UUID.randomUUID(),
                cmd.name(),
                cmd.phone(),
                cmd.email(),
                false,
                Instant.now(),
                Instant.now()
        );
        Supplier saved = supplierRepository.save(supplier);
        if (cmd.productIds() != null && !cmd.productIds().isEmpty()) {
            supplierRepository.linkProducts(saved.id(), cmd.productIds());
        }
        eventPublisher.publishEvent(new SupplierCreatedEvent(
                saved.id(), saved.name(), cmd.actorId(), cmd.tenantId(), Instant.now()));
        return saved;
    }
}
