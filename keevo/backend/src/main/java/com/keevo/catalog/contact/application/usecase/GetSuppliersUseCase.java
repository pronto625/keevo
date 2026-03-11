package com.keevo.catalog.contact.application.usecase;

import com.keevo.catalog.contact.domain.entity.Supplier;
import com.keevo.catalog.contact.domain.port.out.SupplierRepository;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * GetSuppliersUseCase — retrieves a list of suppliers with optional search and archive filter (Story 2.5).
 */
@Service
public class GetSuppliersUseCase {

    private final SupplierRepository supplierRepository;

    public GetSuppliersUseCase(SupplierRepository supplierRepository) {
        this.supplierRepository = supplierRepository;
    }

    public record GetSuppliersQuery(String searchQuery, boolean includeArchived) {}

    public List<Supplier> execute(GetSuppliersQuery query) {
        if (query.searchQuery() != null && !query.searchQuery().isBlank()) {
            return supplierRepository.searchByName(query.searchQuery().trim());
        }
        return supplierRepository.findAll(query.includeArchived());
    }
}
