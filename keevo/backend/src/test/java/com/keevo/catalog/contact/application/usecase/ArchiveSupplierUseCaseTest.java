package com.keevo.catalog.contact.application.usecase;

import com.keevo.catalog.contact.domain.entity.Supplier;
import com.keevo.catalog.contact.domain.event.SupplierArchivedEvent;
import com.keevo.catalog.contact.domain.port.out.SupplierRepository;
import com.keevo.shared.domain.exception.DomainException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * TDD tests for ArchiveSupplierUseCase (Story 2.5).
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ArchiveSupplierUseCase")
class ArchiveSupplierUseCaseTest {

    @Mock SupplierRepository supplierRepository;
    @Mock ApplicationEventPublisher eventPublisher;
    @InjectMocks ArchiveSupplierUseCase useCase;

    private static final Instant NOW = Instant.now();

    @Test
    @DisplayName("archives supplier and publishes SupplierArchivedEvent")
    void should_archive_and_publish_event() {
        UUID supplierId = UUID.randomUUID();
        UUID actorId    = UUID.randomUUID();
        String tenant   = "tenant-xyz";
        Supplier supplier = new Supplier(supplierId, "Acme Co", "+22670000010", null, false, NOW, NOW);
        when(supplierRepository.findById(supplierId)).thenReturn(Optional.of(supplier));

        useCase.execute(supplierId, actorId, tenant);

        verify(supplierRepository).archive(supplierId);
        ArgumentCaptor<SupplierArchivedEvent> captor = ArgumentCaptor.forClass(SupplierArchivedEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        SupplierArchivedEvent event = captor.getValue();
        assertThat(event.supplierId()).isEqualTo(supplierId);
        assertThat(event.actorId()).isEqualTo(actorId);
        assertThat(event.tenantId()).isEqualTo(tenant);
    }

    @Test
    @DisplayName("throws SUPPLIER_NOT_FOUND when supplier does not exist")
    void should_throw_when_supplier_not_found() {
        UUID supplierId = UUID.randomUUID();
        when(supplierRepository.findById(supplierId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> useCase.execute(supplierId, UUID.randomUUID(), "t"))
                .isInstanceOf(DomainException.class)
                .hasMessageContaining("SUPPLIER_NOT_FOUND");
    }
}
