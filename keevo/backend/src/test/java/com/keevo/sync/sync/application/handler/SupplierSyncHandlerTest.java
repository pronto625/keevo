package com.keevo.sync.sync.application.handler;

import com.keevo.catalog.contact.application.usecase.ArchiveSupplierUseCase;
import com.keevo.catalog.contact.application.usecase.UpdateSupplierUseCase;
import com.keevo.catalog.contact.domain.entity.Supplier;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import com.keevo.sync.sync.domain.model.SyncOperation;
import com.keevo.sync.sync.domain.model.SyncOperationStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SupplierSyncHandlerTest {

    @Mock private UpdateSupplierUseCase updateSupplier;
    @Mock private ArchiveSupplierUseCase archiveSupplier;

    private SupplierSyncHandler handler;

    private static final UUID ACTOR_ID = UUID.randomUUID();
    private static final String TENANT_ID = "kv_abc123";

    @BeforeEach
    void setUp() {
        handler = new SupplierSyncHandler(updateSupplier, archiveSupplier);
    }

    @Test
    void handle_updateSupplier_delegatesToUpdateSupplierUseCase() {
        var supplierId = UUID.randomUUID();
        when(updateSupplier.execute(any())).thenReturn(
                new Supplier(supplierId, "Updated Supplier", "+237600000001", null, false, Instant.now(), Instant.now()));

        var op = new SyncOperation("op-1", "UPDATE_SUPPLIER", supplierId.toString(),
                Map.of("supplierId", supplierId.toString(), "name", "Updated Supplier", "phone", "+237600000001"),
                Instant.now());

        var result = handler.handle(op, ACTOR_ID, TENANT_ID);

        assertThat(result.status()).isEqualTo(SyncOperationStatus.APPLIED);
        verify(updateSupplier).execute(any());
    }

    @Test
    void handle_archiveSupplier_delegatesToArchiveSupplierUseCase() {
        var supplierId = UUID.randomUUID();
        var op = new SyncOperation("op-1", "ARCHIVE_SUPPLIER", supplierId.toString(),
                Map.of("supplierId", supplierId.toString()), Instant.now());

        var result = handler.handle(op, ACTOR_ID, TENANT_ID);

        assertThat(result.status()).isEqualTo(SyncOperationStatus.APPLIED);
        verify(archiveSupplier).execute(any(), any(), any());
    }

    @Test
    void handle_duplicateSupplier_returnsDuplicate() {
        var supplierId = UUID.randomUUID();
        var op = new SyncOperation("op-1", "UPDATE_SUPPLIER", supplierId.toString(),
                Map.of("supplierId", supplierId.toString(), "name", "Name", "phone", "+237600000001"),
                Instant.now());

        doThrow(new DomainException(ErrorCode.SUPPLIER_NOT_FOUND))
                .when(updateSupplier).execute(any());

        var result = handler.handle(op, ACTOR_ID, TENANT_ID);

        assertThat(result.status()).isEqualTo(SyncOperationStatus.REJECTED);
        assertThat(result.reason()).isEqualTo("SUPPLIER_NOT_FOUND");
    }

    @Test
    void supportedTypes_containsSupplierTypes() {
        assertThat(handler.supportedTypes()).containsExactlyInAnyOrder("UPDATE_SUPPLIER", "ARCHIVE_SUPPLIER");
    }
}
