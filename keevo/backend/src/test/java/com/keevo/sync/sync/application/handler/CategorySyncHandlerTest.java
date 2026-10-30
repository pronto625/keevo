package com.keevo.sync.sync.application.handler;

import com.keevo.catalog.api.CategoryCommandPort;
import com.keevo.catalog.api.CategoryQueryPort;
import com.keevo.catalog.api.CategoryView;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import com.keevo.shared.infrastructure.persistence.TenantContext;
import com.keevo.shared.application.port.sync.SyncOperation;
import com.keevo.shared.application.port.sync.SyncOperationStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CategorySyncHandlerTest {

    // 12.2e: the catalog / crm / inventory / commerce API adapters require an explicit, current tenant schema
    @org.junit.jupiter.api.BeforeEach
    void bindTenantContext12e() {
        com.keevo.shared.infrastructure.persistence.TenantContext.setCurrentTenant(TENANT_ID);
    }

    @org.junit.jupiter.api.AfterEach
    void clearTenantContext12e() {
        com.keevo.shared.infrastructure.persistence.TenantContext.clear();
    }

    @Mock private CategoryQueryPort categoryQuery;
    @Mock private CategoryCommandPort categoryCommand;

    private CategorySyncHandler handler;

    private static final UUID ACTOR_ID = UUID.randomUUID();
    private static final String TENANT_ID = "kv_test";

    @BeforeEach
    void setUp() {
        handler = new CategorySyncHandler(categoryQuery, categoryCommand);
        SyncTestAuth.owner();
    }

    @AfterEach
    void tearDown() {
        SyncTestAuth.clear();
    }

    private CategoryView sampleCategory(UUID id, String name, boolean isActive) {
        return new CategoryView(id, name, null, isActive, true, Instant.now(), Instant.now());
    }

    // ── CREATE_CATEGORY ─────────────────────────────────────────────────────

    @Test
    void handle_createCategory_createsViaUseCaseWithClientProvidedUUID() {
        var catId = UUID.randomUUID();
        when(categoryQuery.findById(TENANT_ID, catId)).thenReturn(Optional.empty());
        when(categoryCommand.createWithId(any(), any(), any(), any(), any()))
                .thenReturn(sampleCategory(catId, "Électronique", true));

        var op = new SyncOperation("op-1", "CREATE_CATEGORY", catId.toString(),
                Map.of("id", catId.toString(), "name", "Électronique"), Instant.now());

        var result = handler.handle(op, ACTOR_ID, TENANT_ID);

        assertThat(result.status()).isEqualTo(SyncOperationStatus.APPLIED);
        assertThat(result.serverEntityId()).isEqualTo(catId.toString());
        verify(categoryCommand).createWithId(TENANT_ID, catId, "Électronique", null, ACTOR_ID);
    }

    @Test
    void handle_createCategory_withParentId_passesParentToUseCase() {
        var parentId = UUID.randomUUID();
        var catId = UUID.randomUUID();
        when(categoryQuery.findById(TENANT_ID, catId)).thenReturn(Optional.empty());
        when(categoryCommand.createWithId(any(), any(), any(), any(), any()))
                .thenReturn(sampleCategory(catId, "Sous-catégorie", true));

        var op = new SyncOperation("op-2", "CREATE_CATEGORY", catId.toString(),
                Map.of("id", catId.toString(), "name", "Sous-catégorie", "parentId", parentId.toString()),
                Instant.now());

        var result = handler.handle(op, ACTOR_ID, TENANT_ID);

        assertThat(result.status()).isEqualTo(SyncOperationStatus.APPLIED);
        verify(categoryCommand).createWithId(TENANT_ID, catId, "Sous-catégorie", parentId, ACTOR_ID);
    }

    @Test
    void handle_createCategory_idempotent_skipsWhenCategoryAlreadyExists() {
        var catId = UUID.randomUUID();
        when(categoryQuery.findById(TENANT_ID, catId)).thenReturn(Optional.of(sampleCategory(catId, "Électronique", true)));

        var op = new SyncOperation("op-3", "CREATE_CATEGORY", catId.toString(),
                Map.of("id", catId.toString(), "name", "Électronique"), Instant.now());

        var result = handler.handle(op, ACTOR_ID, TENANT_ID);

        assertThat(result.status()).isEqualTo(SyncOperationStatus.APPLIED);
        verifyNoInteractions(categoryCommand);
    }

    @Test
    void handle_createCategory_dedupByNameAndParent_returnsExistingId_noCreate() {
        var catId = UUID.randomUUID();
        var existingId = UUID.randomUUID();
        when(categoryQuery.findById(TENANT_ID, catId)).thenReturn(Optional.empty());
        when(categoryQuery.findByNameAndParentId(TENANT_ID, "Électronique", null))
                .thenReturn(Optional.of(sampleCategory(existingId, "Électronique", true)));

        var op = new SyncOperation("op-3b", "CREATE_CATEGORY", catId.toString(),
                Map.of("id", catId.toString(), "name", "Électronique"), Instant.now());

        var result = handler.handle(op, ACTOR_ID, TENANT_ID);

        assertThat(result.status()).isEqualTo(SyncOperationStatus.APPLIED);
        assertThat(result.serverEntityId()).isEqualTo(existingId.toString());
        verifyNoInteractions(categoryCommand);
    }

    @Test
    void handle_createCategory_missingId_returnsRejected() {
        var op = new SyncOperation("op-4", "CREATE_CATEGORY", null,
                Map.of("name", "Électronique"), Instant.now());

        var result = handler.handle(op, ACTOR_ID, TENANT_ID);

        assertThat(result.status()).isEqualTo(SyncOperationStatus.REJECTED);
        verifyNoInteractions(categoryQuery);
    }

    @Test
    void handle_createCategory_missingName_returnsRejected() {
        var catId = UUID.randomUUID();
        var op = new SyncOperation("op-5", "CREATE_CATEGORY", null,
                Map.of("id", catId.toString()), Instant.now());

        var result = handler.handle(op, ACTOR_ID, TENANT_ID);

        assertThat(result.status()).isEqualTo(SyncOperationStatus.REJECTED);
        verifyNoInteractions(categoryQuery);
    }

    // ── TOGGLE_CATEGORY ─────────────────────────────────────────────────────

    @Test
    void handle_toggleCategory_whenCurrentDiffersFromTarget_callsToggle() {
        var catId = UUID.randomUUID();
        // Current: active=true, target: isActive=false → toggle needed
        when(categoryQuery.findById(TENANT_ID, catId))
                .thenReturn(Optional.of(sampleCategory(catId, "Vêtements", true)));
        when(categoryCommand.toggleActive(TENANT_ID, catId, ACTOR_ID)).thenReturn(sampleCategory(catId, "Vêtements", false));

        var op = new SyncOperation("op-6", "TOGGLE_CATEGORY", catId.toString(),
                Map.of("categoryId", catId.toString(), "isActive", false), Instant.now());

        var result = handler.handle(op, ACTOR_ID, TENANT_ID);

        assertThat(result.status()).isEqualTo(SyncOperationStatus.APPLIED);
        verify(categoryCommand).toggleActive(TENANT_ID, catId, ACTOR_ID);
    }

    @Test
    void handle_toggleCategory_whenAlreadyInTargetState_noToggle() {
        var catId = UUID.randomUUID();
        // Current: active=true, target: isActive=true → no-op
        when(categoryQuery.findById(TENANT_ID, catId))
                .thenReturn(Optional.of(sampleCategory(catId, "Vêtements", true)));

        var op = new SyncOperation("op-7", "TOGGLE_CATEGORY", catId.toString(),
                Map.of("categoryId", catId.toString(), "isActive", true), Instant.now());

        var result = handler.handle(op, ACTOR_ID, TENANT_ID);

        assertThat(result.status()).isEqualTo(SyncOperationStatus.APPLIED);
        verifyNoInteractions(categoryCommand);
    }

    @Test
    void handle_toggleCategory_categoryNotFound_returnsApplied() {
        var catId = UUID.randomUUID();
        when(categoryQuery.findById(TENANT_ID, catId)).thenReturn(Optional.empty());

        var op = new SyncOperation("op-8", "TOGGLE_CATEGORY", catId.toString(),
                Map.of("categoryId", catId.toString(), "isActive", false), Instant.now());

        var result = handler.handle(op, ACTOR_ID, TENANT_ID);

        assertThat(result.status()).isEqualTo(SyncOperationStatus.APPLIED);
        verifyNoInteractions(categoryCommand);
    }

    @Test
    void handle_toggleCategory_missingIsActive_returnsRejected() {
        var catId = UUID.randomUUID();
        var op = new SyncOperation("op-9", "TOGGLE_CATEGORY", null,
                Map.of("categoryId", catId.toString()), Instant.now());

        var result = handler.handle(op, ACTOR_ID, TENANT_ID);

        assertThat(result.status()).isEqualTo(SyncOperationStatus.REJECTED);
        verifyNoInteractions(categoryQuery, categoryCommand);
    }

    @Test
    void handle_toggleCategory_missingCategoryId_returnsRejected() {
        var op = new SyncOperation("op-10", "TOGGLE_CATEGORY", null,
                Map.of("isActive", false), Instant.now());

        var result = handler.handle(op, ACTOR_ID, TENANT_ID);

        assertThat(result.status()).isEqualTo(SyncOperationStatus.REJECTED);
        verifyNoInteractions(categoryCommand);
    }

    // ── RENAME_CATEGORY ─────────────────────────────────────────────────────

    @Test
    void handle_renameCategory_delegatesToRenameCategoryUseCase() {
        var catId = UUID.randomUUID();
        when(categoryCommand.rename(any(), any(), any(), any())).thenReturn(sampleCategory(catId, "Nouveau nom", true));

        var op = new SyncOperation("op-11", "RENAME_CATEGORY", catId.toString(),
                Map.of("categoryId", catId.toString(), "name", "Nouveau nom"), Instant.now());

        var result = handler.handle(op, ACTOR_ID, TENANT_ID);

        assertThat(result.status()).isEqualTo(SyncOperationStatus.APPLIED);
        verify(categoryCommand).rename(eq(TENANT_ID), eq(catId), any(), eq(ACTOR_ID));
    }

    @Test
    void handle_renameCategory_unknownId_isRejectedCategoryNotFound() {
        var catId = UUID.randomUUID();
        when(categoryCommand.rename(any(), any(), any(), any()))
                .thenThrow(new DomainException(ErrorCode.CATEGORY_NOT_FOUND, "categoryId=" + catId));

        var op = new SyncOperation("op-11b", "RENAME_CATEGORY", catId.toString(),
                Map.of("categoryId", catId.toString(), "name", "Nouveau nom"), Instant.now());

        var result = handler.handle(op, ACTOR_ID, TENANT_ID);

        assertThat(result.status()).isEqualTo(SyncOperationStatus.REJECTED);
        assertThat(result.reason()).isEqualTo("CATEGORY_NOT_FOUND");
    }

    @Test
    void handle_renameCategory_missingName_returnsRejected() {
        var catId = UUID.randomUUID();
        var op = new SyncOperation("op-12", "RENAME_CATEGORY", catId.toString(),
                Map.of("categoryId", catId.toString()), Instant.now());

        var result = handler.handle(op, ACTOR_ID, TENANT_ID);

        assertThat(result.status()).isEqualTo(SyncOperationStatus.REJECTED);
        verifyNoInteractions(categoryCommand);
    }

    // ── Metadata ─────────────────────────────────────────────────────────────

    @Test
    void supportedTypes_containsAllCategoryTypes() {
        assertThat(handler.supportedTypes())
                .containsExactlyInAnyOrder("CREATE_CATEGORY", "TOGGLE_CATEGORY", "RENAME_CATEGORY");
    }

    // ── v1s-12-10: TOGGLE_CATEGORY is OWNER-only ────────────────────────────

    @Test
    void handle_toggleCategory_byEmployee_isRejectedForbidden_withoutRepositoryLookup() {
        SyncTestAuth.employee(UUID.randomUUID());
        var catId = UUID.randomUUID();
        var op = new SyncOperation("op-e", "TOGGLE_CATEGORY", catId.toString(),
                Map.of("categoryId", catId.toString(), "isActive", false), Instant.now());

        var result = handler.handle(op, ACTOR_ID, TENANT_ID);

        assertThat(result.status()).isEqualTo(SyncOperationStatus.REJECTED);
        assertThat(result.reason()).isEqualTo("FORBIDDEN");
        verifyNoInteractions(categoryQuery, categoryCommand);
    }

    @Test
    void handle_toggleCategory_withoutAuthentication_isRejectedForbidden() {
        SyncTestAuth.clear();
        var catId = UUID.randomUUID();
        var op = new SyncOperation("op-n", "TOGGLE_CATEGORY", catId.toString(),
                Map.of("categoryId", catId.toString(), "isActive", false), Instant.now());

        var result = handler.handle(op, ACTOR_ID, TENANT_ID);

        assertThat(result.status()).isEqualTo(SyncOperationStatus.REJECTED);
        assertThat(result.reason()).isEqualTo("FORBIDDEN");
        verifyNoInteractions(categoryQuery, categoryCommand);
    }
}
