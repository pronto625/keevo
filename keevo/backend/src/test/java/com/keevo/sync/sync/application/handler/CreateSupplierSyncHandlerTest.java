package com.keevo.sync.sync.application.handler;

import com.keevo.crm.api.CreateSupplierInput;
import com.keevo.crm.api.SupplierCommandPort;
import com.keevo.crm.api.SupplierQueryPort;
import com.keevo.crm.api.SupplierView;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import com.keevo.sync.sync.domain.model.SyncOperation;
import com.keevo.sync.sync.domain.model.SyncOperationStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CreateSupplierSyncHandlerTest {

    @Mock private SupplierQueryPort supplierQuery;
    @Mock private SupplierCommandPort supplierCommand;

    private CreateSupplierSyncHandler handler;

    private static final UUID ACTOR_ID = UUID.randomUUID();
    private static final String TENANT_ID = "kv_test";

    @BeforeEach
    void setUp() {
        handler = new CreateSupplierSyncHandler(supplierQuery, supplierCommand);
    }

    private SupplierView sampleSupplier(UUID id) {
        return new SupplierView(id, "Distributeur SA", "+237690000099", null, false, List.of(),
                Instant.now(), Instant.now());
    }

    @Test
    void handle_createSupplier_savesWithClientProvidedUUID() {
        var supplierId = UUID.randomUUID();
        when(supplierQuery.existsById(supplierId)).thenReturn(false);
        when(supplierCommand.createWithId(any())).thenReturn(sampleSupplier(supplierId));

        var op = new SyncOperation("op-1", "CREATE_SUPPLIER", supplierId.toString(),
                Map.of("id", supplierId.toString(), "name", "Distributeur SA", "phone", "+237690000099"),
                Instant.now());

        var result = handler.handle(op, ACTOR_ID, TENANT_ID);

        assertThat(result.status()).isEqualTo(SyncOperationStatus.APPLIED);
        assertThat(result.serverEntityId()).isEqualTo(supplierId.toString());
        var captor = ArgumentCaptor.forClass(CreateSupplierInput.class);
        verify(supplierCommand).createWithId(captor.capture());
        assertThat(captor.getValue().id()).isEqualTo(supplierId);
        assertThat(captor.getValue().productIds()).isEmpty();
    }

    @Test
    void handle_createSupplier_withProductIds_linksProducts() {
        var supplierId = UUID.randomUUID();
        var prodId1 = UUID.randomUUID();
        var prodId2 = UUID.randomUUID();
        when(supplierQuery.existsById(supplierId)).thenReturn(false);
        when(supplierCommand.createWithId(any())).thenReturn(sampleSupplier(supplierId));

        var op = new SyncOperation("op-2", "CREATE_SUPPLIER", supplierId.toString(),
                Map.of("id", supplierId.toString(), "name", "Fournisseur X", "phone", "+237690000099",
                        "productIds", List.of(prodId1.toString(), prodId2.toString())),
                Instant.now());

        var result = handler.handle(op, ACTOR_ID, TENANT_ID);

        assertThat(result.status()).isEqualTo(SyncOperationStatus.APPLIED);
        var captor = ArgumentCaptor.forClass(CreateSupplierInput.class);
        verify(supplierCommand).createWithId(captor.capture());
        assertThat(captor.getValue().productIds()).containsExactly(prodId1, prodId2);
    }

    @Test
    void handle_createSupplier_idempotent_skipsWhenAlreadyExists() {
        var supplierId = UUID.randomUUID();
        when(supplierQuery.existsById(supplierId)).thenReturn(true);

        var op = new SyncOperation("op-3", "CREATE_SUPPLIER", supplierId.toString(),
                Map.of("id", supplierId.toString(), "name", "Distributeur SA", "phone", "+237690000099"),
                Instant.now());

        var result = handler.handle(op, ACTOR_ID, TENANT_ID);

        assertThat(result.status()).isEqualTo(SyncOperationStatus.APPLIED);
        verifyNoInteractions(supplierCommand);
    }

    @Test
    void handle_createSupplier_missingId_returnsRejected() {
        var op = new SyncOperation("op-4", "CREATE_SUPPLIER", null,
                Map.of("name", "X", "phone", "+237690000099"),
                Instant.now());

        var result = handler.handle(op, ACTOR_ID, TENANT_ID);

        assertThat(result.status()).isEqualTo(SyncOperationStatus.REJECTED);
        verifyNoInteractions(supplierQuery, supplierCommand);
    }

    @Test
    void handle_createSupplier_invalidPhone_returnsRejected() {
        var supplierId = UUID.randomUUID();
        when(supplierQuery.existsById(supplierId)).thenReturn(false);
        when(supplierCommand.createWithId(any()))
                .thenThrow(new DomainException(ErrorCode.VALIDATION_ERROR, "Phone must be E.164 format"));

        var op = new SyncOperation("op-5", "CREATE_SUPPLIER", supplierId.toString(),
                Map.of("id", supplierId.toString(), "name", "X", "phone", "notE164"),
                Instant.now());

        var result = handler.handle(op, ACTOR_ID, TENANT_ID);

        assertThat(result.status()).isEqualTo(SyncOperationStatus.REJECTED);
        assertThat(result.reason()).isEqualTo("VALIDATION_ERROR");
    }

    @Test
    void supportedTypes_containsCreateSupplier() {
        assertThat(handler.supportedTypes()).containsExactly("CREATE_SUPPLIER");
    }

    // ── v1s-13-8: @NotBlank phone parity with the REST DTO ─────────────────

    @Test
    void handle_blankPhone_returnsRejected() {
        var op = new SyncOperation("op-blank", "CREATE_SUPPLIER", null,
                Map.of("id", UUID.randomUUID().toString(), "name", "Alice", "phone", "  "),
                Instant.now());

        var result = handler.handle(op, ACTOR_ID, TENANT_ID);

        assertThat(result.status()).isEqualTo(SyncOperationStatus.REJECTED);
        verifyNoInteractions(supplierQuery, supplierCommand);
    }
}
