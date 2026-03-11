package com.keevo.catalog.contact.application.usecase;

import com.keevo.catalog.contact.domain.entity.Supplier;
import com.keevo.catalog.contact.domain.event.SupplierCreatedEvent;
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
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * TDD tests for CreateSupplierUseCase (Story 2.5).
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("CreateSupplierUseCase")
class CreateSupplierUseCaseTest {

    @Mock SupplierRepository supplierRepository;
    @Mock ApplicationEventPublisher eventPublisher;
    @InjectMocks CreateSupplierUseCase useCase;

    private static final Instant NOW = Instant.now();

    @Test
    @DisplayName("saves supplier and publishes SupplierCreatedEvent")
    void should_create_supplier_and_publish_event() {
        UUID id = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();
        Supplier saved = new Supplier(id, "Acme Co", "+22670000010", null, false, NOW, NOW);
        when(supplierRepository.save(any())).thenReturn(saved);

        var cmd = new CreateSupplierUseCase.CreateSupplierCommand(
                "Acme Co", "+22670000010", null, List.of(), actorId, "tenant-1");
        Supplier result = useCase.execute(cmd);

        assertThat(result.id()).isEqualTo(id);
        verify(supplierRepository).save(any());
        ArgumentCaptor<SupplierCreatedEvent> captor = ArgumentCaptor.forClass(SupplierCreatedEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertThat(captor.getValue().name()).isEqualTo("Acme Co");
        assertThat(captor.getValue().actorId()).isEqualTo(actorId);
    }

    @Test
    @DisplayName("links products when productIds list is non-empty")
    void should_link_products_when_provided() {
        UUID id = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        Supplier saved = new Supplier(id, "Acme Co", "+22670000010", null, false, NOW, NOW);
        when(supplierRepository.save(any())).thenReturn(saved);

        var cmd = new CreateSupplierUseCase.CreateSupplierCommand(
                "Acme Co", "+22670000010", null, List.of(productId), UUID.randomUUID(), "t");
        useCase.execute(cmd);

        verify(supplierRepository).linkProducts(id, List.of(productId));
    }

    @Test
    @DisplayName("throws VALIDATION_ERROR for blank supplier name")
    void should_throw_for_blank_name() {
        var cmd = new CreateSupplierUseCase.CreateSupplierCommand(
                "", "+22670000010", null, List.of(), UUID.randomUUID(), "t");

        assertThatThrownBy(() -> useCase.execute(cmd))
                .isInstanceOf(DomainException.class);
    }
}
