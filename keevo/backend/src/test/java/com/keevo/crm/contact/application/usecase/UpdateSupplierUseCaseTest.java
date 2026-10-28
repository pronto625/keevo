package com.keevo.crm.contact.application.usecase;

import com.keevo.crm.contact.domain.entity.Supplier;
import com.keevo.crm.contact.domain.port.out.SupplierRepository;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * TDD tests for UpdateSupplierUseCase (Story 2.5).
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("UpdateSupplierUseCase")
class UpdateSupplierUseCaseTest {

    @Mock SupplierRepository supplierRepository;
    @Mock org.springframework.context.ApplicationEventPublisher eventPublisher;
    @InjectMocks UpdateSupplierUseCase useCase;

    private static final Instant NOW = Instant.now();

    @Test
    @DisplayName("updates provided fields and saves")
    void should_update_fields_and_save() {
        UUID id = UUID.randomUUID();
        UUID p1 = UUID.randomUUID();
        Supplier existing = new Supplier(id, "Acme Co", "+22670000010", null, false, NOW, NOW);
        when(supplierRepository.findById(id)).thenReturn(Optional.of(existing));
        when(supplierRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        var cmd = new UpdateSupplierUseCase.UpdateSupplierCommand(
                id, "Acme Updated", null, "acme@mail.com", List.of(p1), UUID.randomUUID());
        Supplier result = useCase.execute(cmd);

        assertThat(result.name()).isEqualTo("Acme Updated");
        assertThat(result.phone()).isEqualTo("+22670000010"); // unchanged
        assertThat(result.email()).isEqualTo("acme@mail.com");
        verify(supplierRepository).save(any());
        verify(supplierRepository).linkProducts(id, List.of(p1));
    }

    @Test
    @DisplayName("throws SUPPLIER_NOT_FOUND when supplier does not exist")
    void should_throw_when_supplier_not_found() {
        UUID id = UUID.randomUUID();
        when(supplierRepository.findById(id)).thenReturn(Optional.empty());
        var cmd = new UpdateSupplierUseCase.UpdateSupplierCommand(
                id, null, null, null, null, UUID.randomUUID());

        assertThatThrownBy(() -> useCase.execute(cmd))
                .isInstanceOf(DomainException.class)
                .hasMessageContaining("SUPPLIER_NOT_FOUND");
    }

    @Test
    @DisplayName("null command fields keep existing values")
    void should_keep_existing_values_when_null_fields() {
        UUID id = UUID.randomUUID();
        Supplier existing = new Supplier(id, "Bazar", "+22670000020", "bazar@x.com", false, NOW, NOW);
        when(supplierRepository.findById(id)).thenReturn(Optional.of(existing));
        when(supplierRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        var cmd = new UpdateSupplierUseCase.UpdateSupplierCommand(
                id, null, null, null, null, UUID.randomUUID());
        Supplier result = useCase.execute(cmd);

        assertThat(result.name()).isEqualTo("Bazar");
        assertThat(result.phone()).isEqualTo("+22670000020");
        assertThat(result.email()).isEqualTo("bazar@x.com");
    }

    /** J10 — info change and product links change are both recorded, ids never exposed. */
    @Test
    @DisplayName("records SUPPLIER_UPDATED (changed fields) and SUPPLIER_PRODUCTS_UPDATED (counts)")
    void should_publish_audit_events_for_changes() {
        UUID id = UUID.randomUUID();
        UUID keep = UUID.randomUUID();
        UUID drop = UUID.randomUUID();
        UUID add1 = UUID.randomUUID();
        UUID add2 = UUID.randomUUID();
        Supplier existing = new Supplier(id, "Acme", "+22670000010", null, false, NOW, NOW);
        when(supplierRepository.findById(id)).thenReturn(Optional.of(existing));
        when(supplierRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(supplierRepository.findProductIdsBySupplier(id)).thenReturn(List.of(keep, drop));

        useCase.execute(new UpdateSupplierUseCase.UpdateSupplierCommand(
                id, null, "+22670000099", null, List.of(keep, add1, add2), UUID.randomUUID()));

        var captor = org.mockito.ArgumentCaptor.forClass(Object.class);
        verify(eventPublisher, org.mockito.Mockito.times(2)).publishEvent(captor.capture());
        var info = (com.keevo.crm.contact.domain.event.SupplierUpdatedEvent) captor.getAllValues().get(0);
        assertThat(info.after()).containsOnlyKeys("phone");
        assertThat(info.before().get("phone")).isEqualTo("+22670000010");
        assertThat(info.supplierName()).isEqualTo("Acme");
        var links = (com.keevo.crm.contact.domain.event.SupplierProductsChangedEvent) captor.getAllValues().get(1);
        assertThat(links.previousCount()).isEqualTo(2);
        assertThat(links.newCount()).isEqualTo(3);
        assertThat(links.added()).isEqualTo(2);
        assertThat(links.removed()).isEqualTo(1);
    }

    @Test
    @DisplayName("no real change → nothing recorded")
    void should_not_publish_when_nothing_changes() {
        UUID id = UUID.randomUUID();
        UUID p = UUID.randomUUID();
        Supplier existing = new Supplier(id, "Acme", "+22670000010", null, false, NOW, NOW);
        when(supplierRepository.findById(id)).thenReturn(Optional.of(existing));
        when(supplierRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(supplierRepository.findProductIdsBySupplier(id)).thenReturn(List.of(p));

        useCase.execute(new UpdateSupplierUseCase.UpdateSupplierCommand(
                id, "Acme", "+22670000010", null, List.of(p), UUID.randomUUID()));

        org.mockito.Mockito.verifyNoInteractions(eventPublisher);
    }
}
