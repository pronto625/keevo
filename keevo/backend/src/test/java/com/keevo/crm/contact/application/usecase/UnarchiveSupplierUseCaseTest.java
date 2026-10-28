package com.keevo.crm.contact.application.usecase;

import com.keevo.crm.contact.domain.entity.Supplier;
import com.keevo.crm.contact.domain.event.SupplierUnarchivedEvent;
import com.keevo.crm.contact.domain.port.out.SupplierRepository;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("UnarchiveSupplierUseCase")
class UnarchiveSupplierUseCaseTest {

    @Mock SupplierRepository supplierRepository;
    @Mock ApplicationEventPublisher eventPublisher;
    @InjectMocks UnarchiveSupplierUseCase useCase;

    private static final Instant NOW = Instant.now();

    @Test
    @DisplayName("restores an archived supplier and publishes SupplierUnarchivedEvent")
    void restoresAndPublishes() {
        UUID id = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();
        when(supplierRepository.findById(id)).thenReturn(Optional.of(supplier(id, true)));

        useCase.execute(id, actorId, "tenant-xyz");

        verify(supplierRepository).unarchive(id);
        ArgumentCaptor<SupplierUnarchivedEvent> captor = ArgumentCaptor.forClass(SupplierUnarchivedEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertThat(captor.getValue().supplierId()).isEqualTo(id);
        assertThat(captor.getValue().name()).isEqualTo("Alice");
        assertThat(captor.getValue().actorId()).isEqualTo(actorId);
        assertThat(captor.getValue().tenantId()).isEqualTo("tenant-xyz");
    }

    @Test
    @DisplayName("an already active supplier (replayed operation) is left as is, no audit")
    void alreadyActiveIsNoOp() {
        UUID id = UUID.randomUUID();
        when(supplierRepository.findById(id)).thenReturn(Optional.of(supplier(id, false)));

        useCase.execute(id, UUID.randomUUID(), "t");

        verify(supplierRepository, never()).unarchive(any());
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    @DisplayName("throws SUPPLIER_NOT_FOUND when the supplier does not exist")
    void unknown() {
        UUID id = UUID.randomUUID();
        when(supplierRepository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> useCase.execute(id, UUID.randomUUID(), "t"))
                .isInstanceOf(DomainException.class)
                .hasMessageContaining("SUPPLIER_NOT_FOUND");
    }

    private static Supplier supplier(UUID id, boolean archived) {
        return new Supplier(id, "Alice", "+22670000010", null, archived, NOW, NOW);
    }
}
