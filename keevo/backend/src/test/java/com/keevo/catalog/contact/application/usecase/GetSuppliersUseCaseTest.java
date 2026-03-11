package com.keevo.catalog.contact.application.usecase;

import com.keevo.catalog.contact.domain.entity.Supplier;
import com.keevo.catalog.contact.domain.port.out.SupplierRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * TDD tests for GetSuppliersUseCase (Story 2.5).
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("GetSuppliersUseCase")
class GetSuppliersUseCaseTest {

    @Mock SupplierRepository supplierRepository;
    @InjectMocks GetSuppliersUseCase useCase;

    private static final Instant NOW = Instant.now();

    Supplier sampleSupplier(UUID id) {
        return new Supplier(id, "Acme Co", "+22670000010", null, false, NOW, NOW);
    }

    @Test
    @DisplayName("blank searchQuery delegates to findAll(false)")
    void should_call_find_all_when_no_search_query() {
        UUID id = UUID.randomUUID();
        when(supplierRepository.findAll(false)).thenReturn(List.of(sampleSupplier(id)));

        List<Supplier> result = useCase.execute(new GetSuppliersUseCase.GetSuppliersQuery("", false));

        assertThat(result).hasSize(1);
        verify(supplierRepository).findAll(false);
        verify(supplierRepository, never()).searchByName(any());
    }

    @Test
    @DisplayName("non-blank searchQuery delegates to searchByName")
    void should_call_search_when_query_provided() {
        UUID id = UUID.randomUUID();
        when(supplierRepository.searchByName("Acme")).thenReturn(List.of(sampleSupplier(id)));

        List<Supplier> result = useCase.execute(new GetSuppliersUseCase.GetSuppliersQuery("Acme", false));

        assertThat(result).hasSize(1);
        verify(supplierRepository).searchByName("Acme");
    }

    @Test
    @DisplayName("includeArchived=true passes through to repository")
    void should_pass_include_archived_flag() {
        when(supplierRepository.findAll(true)).thenReturn(List.of());

        useCase.execute(new GetSuppliersUseCase.GetSuppliersQuery(null, true));

        verify(supplierRepository).findAll(true);
    }
}
