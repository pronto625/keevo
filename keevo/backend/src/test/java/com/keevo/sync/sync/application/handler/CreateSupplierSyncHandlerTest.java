package com.keevo.sync.sync.application.handler;

import com.keevo.catalog.contact.domain.entity.Supplier;
import com.keevo.catalog.contact.domain.port.out.SupplierRepository;
import com.keevo.sync.sync.domain.model.SyncOperation;
import com.keevo.sync.sync.domain.model.SyncOperationStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CreateSupplierSyncHandlerTest {

    @Mock private SupplierRepository supplierRepository;

    private CreateSupplierSyncHandler handler;

    private static final UUID ACTOR_ID = UUID.randomUUID();
    private static final String TENANT_ID = "kv_test";

    @BeforeEach
    void setUp() {
        handler = new CreateSupplierSyncHandler(supplierRepository);
    }

    private Supplier sampleSupplier(UUID id) {
        return new Supplier(id, "Distributeur SA", "+237690000099", null, false,
                Instant.now(), Instant.now());
    }

    @Test
    void handle_createSupplier_savesWithClientProvidedUUID() {
        var supplierId = UUID.randomUUID();
        when(supplierRepository.findById(supplierId)).thenReturn(Optional.empty());
        when(supplierRepository.save(any())).thenReturn(sampleSupplier(supplierId));

        var op = new SyncOperation("op-1", "CREATE_SUPPLIER", supplierId.toString(),
                Map.of("id", supplierId.toString(), "name", "Distributeur SA", "phone", "+237690000099"),
                Instant.now());

        var result = handler.handle(op, ACTOR_ID, TENANT_ID);

        assertThat(result.status()).isEqualTo(SyncOperationStatus.APPLIED);
        assertThat(result.serverEntityId()).isEqualTo(supplierId.toString());
        verify(supplierRepository).save(argThat(s -> supplierId.equals(s.id())));
        verify(supplierRepository, never()).linkProducts(any(), any());
    }

    @Test
    void handle_createSupplier_withProductIds_linksProducts() {
        var supplierId = UUID.randomUUID();
        var prodId1 = UUID.randomUUID().toString();
        var prodId2 = UUID.randomUUID().toString();
        when(supplierRepository.findById(supplierId)).thenReturn(Optional.empty());
        when(supplierRepository.save(any())).thenReturn(sampleSupplier(supplierId));

        var op = new SyncOperation("op-2", "CREATE_SUPPLIER", supplierId.toString(),
                Map.of("id", supplierId.toString(), "name", "Fournisseur X", "phone", "+237690000099",
                        "productIds", List.of(prodId1, prodId2)),
                Instant.now());

        var result = handler.handle(op, ACTOR_ID, TENANT_ID);

        assertThat(result.status()).isEqualTo(SyncOperationStatus.APPLIED);
        verify(supplierRepository).linkProducts(eq(supplierId), argThat(ids -> ids.size() == 2));
    }

    @Test
    void handle_createSupplier_idempotent_skipsWhenAlreadyExists() {
        var supplierId = UUID.randomUUID();
        when(supplierRepository.findById(supplierId)).thenReturn(Optional.of(sampleSupplier(supplierId)));

        var op = new SyncOperation("op-3", "CREATE_SUPPLIER", supplierId.toString(),
                Map.of("id", supplierId.toString(), "name", "Distributeur SA", "phone", "+237690000099"),
                Instant.now());

        var result = handler.handle(op, ACTOR_ID, TENANT_ID);

        assertThat(result.status()).isEqualTo(SyncOperationStatus.APPLIED);
        verify(supplierRepository, never()).save(any());
        verify(supplierRepository, never()).linkProducts(any(), any());
    }

    @Test
    void handle_createSupplier_missingId_returnsRejected() {
        var op = new SyncOperation("op-4", "CREATE_SUPPLIER", null,
                Map.of("name", "X", "phone", "+237690000099"),
                Instant.now());

        var result = handler.handle(op, ACTOR_ID, TENANT_ID);

        assertThat(result.status()).isEqualTo(SyncOperationStatus.REJECTED);
        verifyNoInteractions(supplierRepository);
    }

    @Test
    void handle_createSupplier_invalidPhone_returnsRejected() {
        var supplierId = UUID.randomUUID();
        when(supplierRepository.findById(supplierId)).thenReturn(Optional.empty());

        var op = new SyncOperation("op-5", "CREATE_SUPPLIER", supplierId.toString(),
                Map.of("id", supplierId.toString(), "name", "X", "phone", "notE164"),
                Instant.now());

        var result = handler.handle(op, ACTOR_ID, TENANT_ID);

        assertThat(result.status()).isEqualTo(SyncOperationStatus.REJECTED);
        verify(supplierRepository, never()).save(any());
    }

    @Test
    void supportedTypes_containsCreateSupplier() {
        assertThat(handler.supportedTypes()).containsExactly("CREATE_SUPPLIER");
    }
}
