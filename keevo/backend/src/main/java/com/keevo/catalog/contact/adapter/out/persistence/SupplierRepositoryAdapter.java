package com.keevo.catalog.contact.adapter.out.persistence;

import com.keevo.catalog.contact.domain.entity.Supplier;
import com.keevo.catalog.contact.domain.port.out.SupplierRepository;
import com.keevo.shared.infrastructure.persistence.entity.ProductSupplierJpaEntity;
import com.keevo.shared.infrastructure.persistence.entity.SupplierJpaEntity;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * SupplierRepositoryAdapter — JPA implementation of {@link SupplierRepository} (Story 2.5).
 */
@Component
public class SupplierRepositoryAdapter implements SupplierRepository {

    private final SupplierSpringRepository supplierRepo;
    private final ProductSupplierSpringRepository productSupplierRepo;

    public SupplierRepositoryAdapter(SupplierSpringRepository supplierRepo,
                                     ProductSupplierSpringRepository productSupplierRepo) {
        this.supplierRepo = supplierRepo;
        this.productSupplierRepo = productSupplierRepo;
    }

    @Override
    public Supplier save(Supplier supplier) {
        return toDomain(supplierRepo.save(toEntity(supplier)));
    }

    @Override
    public Optional<Supplier> findById(UUID id) {
        return supplierRepo.findById(id).map(this::toDomain);
    }

    @Override
    public List<Supplier> findAll(boolean includeArchived) {
        return supplierRepo.findAllFiltered(includeArchived)
                .stream().map(this::toDomain).toList();
    }

    @Override
    public List<Supplier> searchByName(String query) {
        return supplierRepo.searchByName(query)
                .stream().map(this::toDomain).toList();
    }

    @Override
    public void archive(UUID id) {
        supplierRepo.findById(id).ifPresent(e -> {
            e.setArchived(true);
            e.setUpdatedAt(Instant.now());
            supplierRepo.save(e);
        });
    }

    @Override
    public List<UUID> findProductIdsBySupplier(UUID supplierId) {
        return productSupplierRepo.findProductIdsBySupplierId(supplierId);
    }

    @Override
    public Optional<Supplier> findByProductId(UUID productId) {
        return productSupplierRepo.findSupplierIdByProductId(productId)
                .flatMap(supplierRepo::findById)
                .map(this::toDomain);
    }

    @Override
    @Transactional
    public void linkProducts(UUID supplierId, List<UUID> productIds) {
        productSupplierRepo.deleteAllBySupplierId(supplierId);
        List<ProductSupplierJpaEntity> links = productIds.stream()
                .map(pid -> new ProductSupplierJpaEntity(pid, supplierId))
                .collect(Collectors.toList());
        productSupplierRepo.saveAll(links);
    }

    // ── Mapping ───────────────────────────────────────────────────────────────

    private SupplierJpaEntity toEntity(Supplier supplier) {
        return new SupplierJpaEntity(
                supplier.id(), supplier.name(), supplier.phone(),
                supplier.email(), supplier.archived(),
                supplier.createdAt(), supplier.updatedAt());
    }

    private Supplier toDomain(SupplierJpaEntity e) {
        return new Supplier(e.getId(), e.getName(), e.getPhone(),
                e.getEmail(), e.getArchived(), e.getCreatedAt(), e.getUpdatedAt());
    }
}
