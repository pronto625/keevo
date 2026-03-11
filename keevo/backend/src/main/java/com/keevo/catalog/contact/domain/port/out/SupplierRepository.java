package com.keevo.catalog.contact.domain.port.out;

import com.keevo.catalog.contact.domain.entity.Supplier;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * SupplierRepository — output port for supplier persistence (Hexagonal Architecture).
 */
public interface SupplierRepository {

    Supplier save(Supplier supplier);

    Optional<Supplier> findById(UUID id);

    List<Supplier> findAll(boolean includeArchived);

    List<Supplier> searchByName(String query);

    void archive(UUID id);

    List<UUID> findProductIdsBySupplier(UUID supplierId);

    void linkProducts(UUID supplierId, List<UUID> productIds);

    Optional<Supplier> findByProductId(UUID productId);
}
