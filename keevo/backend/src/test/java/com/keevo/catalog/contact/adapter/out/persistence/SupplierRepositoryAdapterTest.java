package com.keevo.catalog.contact.adapter.out.persistence;

import com.keevo.catalog.contact.domain.entity.Supplier;
import com.keevo.shared.infrastructure.persistence.entity.ProductSupplierJpaEntity;
import com.keevo.shared.infrastructure.persistence.entity.SupplierJpaEntity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for SupplierRepositoryAdapter (Story 2.5) — Mockito, no DB.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("SupplierRepositoryAdapter")
class SupplierRepositoryAdapterTest {

    @Mock SupplierSpringRepository supplierRepo;
    @Mock ProductSupplierSpringRepository productSupplierRepo;
    @InjectMocks SupplierRepositoryAdapter adapter;

    private static final UUID ID  = UUID.randomUUID();
    private static final Instant NOW = Instant.now();

    private SupplierJpaEntity sampleEntity() {
        return new SupplierJpaEntity(ID, "Supplier Corp", "+33612345678", "info@s.com", false, NOW, NOW);
    }

    private Supplier sampleDomain() {
        return new Supplier(ID, "Supplier Corp", "+33612345678", "info@s.com", false, NOW, NOW);
    }

    @Test
    @DisplayName("save() delegates to supplierRepo.save and maps result")
    void should_save_and_return_domain_supplier() {
        when(supplierRepo.save(any())).thenReturn(sampleEntity());

        Supplier result = adapter.save(sampleDomain());

        assertThat(result.id()).isEqualTo(ID);
        assertThat(result.name()).isEqualTo("Supplier Corp");
    }

    @Test
    @DisplayName("findById() returns mapped supplier when entity exists")
    void should_find_by_id() {
        when(supplierRepo.findById(ID)).thenReturn(Optional.of(sampleEntity()));

        Optional<Supplier> result = adapter.findById(ID);

        assertThat(result).isPresent();
        assertThat(result.get().id()).isEqualTo(ID);
    }

    @Test
    @DisplayName("findById() returns empty Optional when absent")
    void should_return_empty_when_not_found() {
        when(supplierRepo.findById(any())).thenReturn(Optional.empty());
        assertThat(adapter.findById(UUID.randomUUID())).isEmpty();
    }

    @Test
    @DisplayName("findAll(false) delegates to findAllFiltered(false)")
    void should_delegate_find_all() {
        when(supplierRepo.findAllFiltered(false)).thenReturn(List.of(sampleEntity()));

        List<Supplier> list = adapter.findAll(false);

        assertThat(list).hasSize(1);
        verify(supplierRepo).findAllFiltered(false);
    }

    @Test
    @DisplayName("archive() marks entity as archived and saves")
    void should_archive_supplier() {
        SupplierJpaEntity entity = sampleEntity();
        when(supplierRepo.findById(ID)).thenReturn(Optional.of(entity));

        adapter.archive(ID);

        assertThat(entity.getArchived()).isTrue();
        verify(supplierRepo).save(entity);
    }

    @Test
    @DisplayName("findProductIdsBySupplier() delegates to productSupplierRepo")
    void should_delegate_find_product_ids() {
        UUID productId = UUID.randomUUID();
        when(productSupplierRepo.findProductIdsBySupplierId(ID)).thenReturn(List.of(productId));

        List<UUID> result = adapter.findProductIdsBySupplier(ID);

        assertThat(result).containsExactly(productId);
    }

    @Test
    @DisplayName("linkProducts() deletes existing links then saves new ones")
    void should_replace_product_links() {
        UUID p1 = UUID.randomUUID();
        UUID p2 = UUID.randomUUID();

        adapter.linkProducts(ID, List.of(p1, p2));

        verify(productSupplierRepo).deleteAllBySupplierId(ID);
        verify(productSupplierRepo).saveAll(argThat((List<ProductSupplierJpaEntity> links) ->
                links.size() == 2));
    }

    @Test
    @DisplayName("linkProducts() with empty list clears all existing links")
    void should_clear_links_when_empty_list() {
        adapter.linkProducts(ID, List.of());

        verify(productSupplierRepo).deleteAllBySupplierId(ID);
        verify(productSupplierRepo).saveAll(argThat((List<ProductSupplierJpaEntity> links) -> links.isEmpty()));
    }
}
