package com.keevo.crm.contact.adapter.in.sync;

import com.keevo.crm.api.SupplierCommandPort;
import com.keevo.crm.api.SupplierView;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import com.keevo.shared.infrastructure.persistence.TenantContext;
import com.keevo.shared.application.port.sync.SyncOperation;
import com.keevo.shared.application.port.sync.SyncOperationStatus;
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

    // 12.2e: the catalog / crm / inventory / commerce API adapters require an explicit, current tenant schema
    @org.junit.jupiter.api.BeforeEach
    void bindTenantContext12e() {
        com.keevo.shared.infrastructure.persistence.TenantContext.setCurrentTenant(TENANT_ID);
    }

    @org.junit.jupiter.api.AfterEach
    void clearTenantContext12e() {
        com.keevo.shared.infrastructure.persistence.TenantContext.clear();
    }

    @Mock private SupplierCommandPort supplierCommand;

    private SupplierSyncHandler handler;

    private static final UUID ACTOR_ID = UUID.randomUUID();
    private static final String TENANT_ID = "kv_abc123";

    @BeforeEach
    void setUp() {
        handler = new SupplierSyncHandler(supplierCommand);
    }

    @Test
    void handle_updateSupplier_delegatesToUpdateSupplierPort() {
        var supplierId = UUID.randomUUID();
        when(supplierCommand.update(any(), any())).thenReturn(
                new SupplierView(supplierId, "Updated Supplier", "+237600000001", null, false, java.util.List.of(), Instant.now(), Instant.now()));

        var op = new SyncOperation("op-1", "UPDATE_SUPPLIER", supplierId.toString(),
                Map.of("supplierId", supplierId.toString(), "name", "Updated Supplier", "phone", "+237600000001"),
                Instant.now());

        var result = handler.handle(op, ACTOR_ID, TENANT_ID);

        assertThat(result.status()).isEqualTo(SyncOperationStatus.APPLIED);
        verify(supplierCommand).update(eq(TENANT_ID), any());
    }

    @Test
    void handle_archiveSupplier_delegatesToArchiveSupplierPort() {
        var supplierId = UUID.randomUUID();
        var op = new SyncOperation("op-1", "ARCHIVE_SUPPLIER", supplierId.toString(),
                Map.of("supplierId", supplierId.toString()), Instant.now());

        var result = handler.handle(op, ACTOR_ID, TENANT_ID);

        assertThat(result.status()).isEqualTo(SyncOperationStatus.APPLIED);
        verify(supplierCommand).archive(eq(TENANT_ID), any(), any());
    }

    @Test
    void handle_duplicateSupplier_returnsDuplicate() {
        var supplierId = UUID.randomUUID();
        var op = new SyncOperation("op-1", "UPDATE_SUPPLIER", supplierId.toString(),
                Map.of("supplierId", supplierId.toString(), "name", "Name", "phone", "+237600000001"),
                Instant.now());

        doThrow(new DomainException(ErrorCode.SUPPLIER_NOT_FOUND))
                .when(supplierCommand).update(any(), any());

        var result = handler.handle(op, ACTOR_ID, TENANT_ID);

        assertThat(result.status()).isEqualTo(SyncOperationStatus.REJECTED);
        assertThat(result.reason()).isEqualTo("SUPPLIER_NOT_FOUND");
    }

    @Test
    void supportedTypes_containsSupplierTypes() {
        assertThat(handler.supportedTypes()).containsExactlyInAnyOrder("UPDATE_SUPPLIER", "ARCHIVE_SUPPLIER", "UNARCHIVE_SUPPLIER");
    }

    @Test
    void handle_unarchiveSupplier_delegatesToUnarchiveSupplierPort() {
        var supplierId = UUID.randomUUID();
        var op = new SyncOperation("op-2", "UNARCHIVE_SUPPLIER", supplierId.toString(),
                Map.of("supplierId", supplierId.toString()), Instant.now());

        var result = handler.handle(op, ACTOR_ID, TENANT_ID);

        assertThat(result.status()).isEqualTo(SyncOperationStatus.APPLIED);
        assertThat(result.serverEntityId()).isEqualTo(supplierId.toString());
        verify(supplierCommand).unarchive(TENANT_ID, supplierId, ACTOR_ID);
        verify(supplierCommand, never()).archive(any(), any(), any());
    }

    @Test
    void handle_unarchiveUnknownSupplier_isRejected() {
        var supplierId = UUID.randomUUID();
        doThrow(new DomainException(ErrorCode.SUPPLIER_NOT_FOUND))
                .when(supplierCommand).unarchive(any(), any(), any());

        var result = handler.handle(new SyncOperation("op-3", "UNARCHIVE_SUPPLIER", supplierId.toString(),
                Map.of("supplierId", supplierId.toString()), Instant.now()), ACTOR_ID, TENANT_ID);

        assertThat(result.status()).isEqualTo(SyncOperationStatus.REJECTED);
        assertThat(result.reason()).isEqualTo("SUPPLIER_NOT_FOUND");
    }
}
