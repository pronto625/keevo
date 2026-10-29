package com.keevo.crm.contact.adapter.in.api;

import com.keevo.crm.api.CreateSupplierInput;
import com.keevo.crm.api.SupplierCommandPort;
import com.keevo.crm.api.SupplierQueryPort;
import com.keevo.crm.api.SupplierView;
import com.keevo.crm.api.UpdateSupplierInput;
import com.keevo.crm.contact.application.usecase.ArchiveSupplierUseCase;
import com.keevo.crm.contact.application.usecase.GetSupplierByProductUseCase;
import com.keevo.crm.contact.application.usecase.GetSupplierProfileUseCase;
import com.keevo.crm.contact.application.usecase.UnarchiveSupplierUseCase;
import com.keevo.crm.contact.application.usecase.UpdateSupplierUseCase;
import com.keevo.crm.contact.domain.entity.Supplier;
import com.keevo.crm.contact.domain.port.out.SupplierRepository;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Implements the published supplier ports by delegating to the crm use cases and repository
 * (Story 12.2a). No transaction of its own: delegates keep theirs, or join the caller's.
 */
@Component
class SupplierApiAdapter implements SupplierQueryPort, SupplierCommandPort {

    private final SupplierRepository supplierRepository;
    private final GetSupplierByProductUseCase getSupplierByProduct;
    private final GetSupplierProfileUseCase getSupplierProfile;
    private final UpdateSupplierUseCase updateSupplier;
    private final ArchiveSupplierUseCase archiveSupplier;
    private final UnarchiveSupplierUseCase unarchiveSupplier;

    SupplierApiAdapter(SupplierRepository supplierRepository,
                       GetSupplierByProductUseCase getSupplierByProduct,
                       GetSupplierProfileUseCase getSupplierProfile,
                       UpdateSupplierUseCase updateSupplier,
                       ArchiveSupplierUseCase archiveSupplier,
                       UnarchiveSupplierUseCase unarchiveSupplier) {
        this.supplierRepository = supplierRepository;
        this.getSupplierByProduct = getSupplierByProduct;
        this.getSupplierProfile = getSupplierProfile;
        this.updateSupplier = updateSupplier;
        this.archiveSupplier = archiveSupplier;
        this.unarchiveSupplier = unarchiveSupplier;
    }

    @Override
    public boolean existsById(UUID supplierId) {
        return supplierRepository.findById(supplierId).isPresent();
    }

    @Override
    public Optional<SupplierView> findByProductId(UUID productId) {
        return getSupplierByProduct.execute(productId)
                .map(supplier -> {
                    var profile = getSupplierProfile.execute(supplier.id());
                    return ContactViews.of(supplier, profile.productIds());
                });
    }

    @Override
    public SupplierView createWithId(CreateSupplierInput in) {
        Instant now = Instant.now();
        Supplier saved = supplierRepository.save(
                new Supplier(in.id(), in.name(), in.phone(), in.email(), false, now, now));
        List<UUID> productIds = in.productIds() != null ? in.productIds() : List.of();
        if (!productIds.isEmpty()) {
            supplierRepository.linkProducts(saved.id(), productIds);
        }
        return ContactViews.of(saved, productIds);
    }

    @Override
    public SupplierView update(UpdateSupplierInput in) {
        Supplier updated = updateSupplier.execute(new UpdateSupplierUseCase.UpdateSupplierCommand(
                in.supplierId(), in.name(), in.phone(), in.email(), in.productIds(), in.actorId()));
        // null productIds = links kept: report the real links (same source as the supplier profile)
        List<UUID> productIds = in.productIds() != null
                ? in.productIds()
                : getSupplierProfile.execute(updated.id()).productIds();
        return ContactViews.of(updated, productIds);
    }

    @Override
    public void archive(UUID supplierId, UUID actorId, String tenantId) {
        archiveSupplier.execute(supplierId, actorId, tenantId);
    }

    @Override
    public void unarchive(UUID supplierId, UUID actorId, String tenantId) {
        unarchiveSupplier.execute(supplierId, actorId, tenantId);
    }
}
