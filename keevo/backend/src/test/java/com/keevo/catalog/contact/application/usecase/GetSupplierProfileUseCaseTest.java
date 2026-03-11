package com.keevo.catalog.contact.application.usecase;

import com.keevo.catalog.contact.domain.entity.Supplier;
import com.keevo.catalog.contact.domain.port.out.SupplierRepository;
import com.keevo.shared.domain.exception.DomainException;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

/**
 * TDD tests for GetSupplierProfileUseCase (Story 2.5).
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("GetSupplierProfileUseCase")
class GetSupplierProfileUseCaseTest {

    @Mock SupplierRepository supplierRepository;
    @InjectMocks GetSupplierProfileUseCase useCase;

    private static final Instant NOW = Instant.now();

    @Test
    @DisplayName("returns supplier profile with linked product IDs")
    void should_return_profile_with_product_ids() {
        UUID id = UUID.randomUUID();
        UUID p1 = UUID.randomUUID();
        Supplier supplier = new Supplier(id, "Acme Co", "+22670000010", null, false, NOW, NOW);
        when(supplierRepository.findById(id)).thenReturn(Optional.of(supplier));
        when(supplierRepository.findProductIdsBySupplier(id)).thenReturn(List.of(p1));

        var result = useCase.execute(id);

        assertThat(result.supplier()).isEqualTo(supplier);
        assertThat(result.productIds()).containsExactly(p1);
    }

    @Test
    @DisplayName("throws SUPPLIER_NOT_FOUND when supplier does not exist")
    void should_throw_when_supplier_not_found() {
        UUID id = UUID.randomUUID();
        when(supplierRepository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> useCase.execute(id))
                .isInstanceOf(DomainException.class)
                .hasMessageContaining("SUPPLIER_NOT_FOUND");
    }
}
