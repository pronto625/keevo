package com.keevo.sync.sync.application.handler;

import com.keevo.catalog.category.application.usecase.RenameCategoryUseCase;
import com.keevo.catalog.category.application.usecase.ToggleCategoryUseCase;
import com.keevo.catalog.category.domain.model.Category;
import com.keevo.catalog.category.domain.port.out.CategoryRepository;
import com.keevo.sync.sync.domain.model.SyncOperation;
import com.keevo.sync.sync.domain.model.SyncOperationStatus;
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

    @Mock private CategoryRepository categoryRepository;
    @Mock private ToggleCategoryUseCase toggleCategory;
    @Mock private RenameCategoryUseCase renameCategory;

    private CategorySyncHandler handler;

    private static final UUID ACTOR_ID = UUID.randomUUID();
    private static final String TENANT_ID = "kv_test";

    @BeforeEach
    void setUp() {
        handler = new CategorySyncHandler(categoryRepository, toggleCategory, renameCategory);
    }

    private Category sampleCategory(UUID id, String name, boolean isActive) {
        return new Category(id, name, null, isActive, true, Instant.now(), Instant.now());
    }

    // ── CREATE_CATEGORY ─────────────────────────────────────────────────────

    @Test
    void handle_createCategory_savesWithClientProvidedUUID() {
        var catId = UUID.randomUUID();
        when(categoryRepository.findById(catId)).thenReturn(Optional.empty());
        when(categoryRepository.save(any())).thenReturn(sampleCategory(catId, "Électronique", true));

        var op = new SyncOperation("op-1", "CREATE_CATEGORY", catId.toString(),
                Map.of("id", catId.toString(), "name", "Électronique"), Instant.now());

        var result = handler.handle(op, ACTOR_ID, TENANT_ID);

        assertThat(result.status()).isEqualTo(SyncOperationStatus.APPLIED);
        assertThat(result.serverEntityId()).isEqualTo(catId.toString());
        verify(categoryRepository).save(argThat(c -> catId.equals(c.id()) && "Électronique".equals(c.name())));
    }

    @Test
    void handle_createCategory_withParentId_passesParentToRepository() {
        var parentId = UUID.randomUUID();
        var catId = UUID.randomUUID();
        when(categoryRepository.findById(catId)).thenReturn(Optional.empty());
        when(categoryRepository.save(any())).thenReturn(sampleCategory(catId, "Sous-catégorie", true));

        var op = new SyncOperation("op-2", "CREATE_CATEGORY", catId.toString(),
                Map.of("id", catId.toString(), "name", "Sous-catégorie", "parentId", parentId.toString()),
                Instant.now());

        var result = handler.handle(op, ACTOR_ID, TENANT_ID);

        assertThat(result.status()).isEqualTo(SyncOperationStatus.APPLIED);
        verify(categoryRepository).save(argThat(c -> catId.equals(c.id()) && parentId.equals(c.parentId())));
    }

    @Test
    void handle_createCategory_idempotent_skipsWhenCategoryAlreadyExists() {
        var catId = UUID.randomUUID();
        when(categoryRepository.findById(catId)).thenReturn(Optional.of(sampleCategory(catId, "Électronique", true)));

        var op = new SyncOperation("op-3", "CREATE_CATEGORY", catId.toString(),
                Map.of("id", catId.toString(), "name", "Électronique"), Instant.now());

        var result = handler.handle(op, ACTOR_ID, TENANT_ID);

        assertThat(result.status()).isEqualTo(SyncOperationStatus.APPLIED);
        verify(categoryRepository, never()).save(any());
    }

    @Test
    void handle_createCategory_missingId_returnsRejected() {
        var op = new SyncOperation("op-4", "CREATE_CATEGORY", null,
                Map.of("name", "Électronique"), Instant.now());

        var result = handler.handle(op, ACTOR_ID, TENANT_ID);

        assertThat(result.status()).isEqualTo(SyncOperationStatus.REJECTED);
        verifyNoInteractions(categoryRepository);
    }

    @Test
    void handle_createCategory_missingName_returnsRejected() {
        var catId = UUID.randomUUID();
        var op = new SyncOperation("op-5", "CREATE_CATEGORY", null,
                Map.of("id", catId.toString()), Instant.now());

        var result = handler.handle(op, ACTOR_ID, TENANT_ID);

        assertThat(result.status()).isEqualTo(SyncOperationStatus.REJECTED);
        verifyNoInteractions(categoryRepository);
    }

    // ── TOGGLE_CATEGORY ─────────────────────────────────────────────────────

    @Test
    void handle_toggleCategory_whenCurrentDiffersFromTarget_callsToggle() {
        var catId = UUID.randomUUID();
        // Current: active=true, target: isActive=false → toggle needed
        when(categoryRepository.findById(catId))
                .thenReturn(Optional.of(sampleCategory(catId, "Vêtements", true)));
        when(toggleCategory.execute(catId)).thenReturn(sampleCategory(catId, "Vêtements", false));

        var op = new SyncOperation("op-6", "TOGGLE_CATEGORY", catId.toString(),
                Map.of("categoryId", catId.toString(), "isActive", false), Instant.now());

        var result = handler.handle(op, ACTOR_ID, TENANT_ID);

        assertThat(result.status()).isEqualTo(SyncOperationStatus.APPLIED);
        verify(toggleCategory).execute(catId);
    }

    @Test
    void handle_toggleCategory_whenAlreadyInTargetState_noToggle() {
        var catId = UUID.randomUUID();
        // Current: active=true, target: isActive=true → no-op
        when(categoryRepository.findById(catId))
                .thenReturn(Optional.of(sampleCategory(catId, "Vêtements", true)));

        var op = new SyncOperation("op-7", "TOGGLE_CATEGORY", catId.toString(),
                Map.of("categoryId", catId.toString(), "isActive", true), Instant.now());

        var result = handler.handle(op, ACTOR_ID, TENANT_ID);

        assertThat(result.status()).isEqualTo(SyncOperationStatus.APPLIED);
        verifyNoInteractions(toggleCategory);
    }

    @Test
    void handle_toggleCategory_categoryNotFound_returnsApplied() {
        var catId = UUID.randomUUID();
        when(categoryRepository.findById(catId)).thenReturn(Optional.empty());

        var op = new SyncOperation("op-8", "TOGGLE_CATEGORY", catId.toString(),
                Map.of("categoryId", catId.toString(), "isActive", false), Instant.now());

        var result = handler.handle(op, ACTOR_ID, TENANT_ID);

        assertThat(result.status()).isEqualTo(SyncOperationStatus.APPLIED);
        verifyNoInteractions(toggleCategory);
    }

    @Test
    void handle_toggleCategory_missingIsActive_returnsRejected() {
        var catId = UUID.randomUUID();
        var op = new SyncOperation("op-9", "TOGGLE_CATEGORY", null,
                Map.of("categoryId", catId.toString()), Instant.now());

        var result = handler.handle(op, ACTOR_ID, TENANT_ID);

        assertThat(result.status()).isEqualTo(SyncOperationStatus.REJECTED);
        verifyNoInteractions(categoryRepository, toggleCategory);
    }

    @Test
    void handle_toggleCategory_missingCategoryId_returnsRejected() {
        var op = new SyncOperation("op-10", "TOGGLE_CATEGORY", null,
                Map.of("isActive", false), Instant.now());

        var result = handler.handle(op, ACTOR_ID, TENANT_ID);

        assertThat(result.status()).isEqualTo(SyncOperationStatus.REJECTED);
        verifyNoInteractions(toggleCategory);
    }

    // ── RENAME_CATEGORY ─────────────────────────────────────────────────────

    @Test
    void handle_renameCategory_delegatesToRenameCategoryUseCase() {
        var catId = UUID.randomUUID();
        when(renameCategory.execute(any(), any())).thenReturn(sampleCategory(catId, "Nouveau nom", true));

        var op = new SyncOperation("op-11", "RENAME_CATEGORY", catId.toString(),
                Map.of("categoryId", catId.toString(), "name", "Nouveau nom"), Instant.now());

        var result = handler.handle(op, ACTOR_ID, TENANT_ID);

        assertThat(result.status()).isEqualTo(SyncOperationStatus.APPLIED);
        verify(renameCategory).execute(eq(catId), any());
    }

    @Test
    void handle_renameCategory_missingName_returnsRejected() {
        var catId = UUID.randomUUID();
        var op = new SyncOperation("op-12", "RENAME_CATEGORY", catId.toString(),
                Map.of("categoryId", catId.toString()), Instant.now());

        var result = handler.handle(op, ACTOR_ID, TENANT_ID);

        assertThat(result.status()).isEqualTo(SyncOperationStatus.REJECTED);
        verifyNoInteractions(renameCategory);
    }

    // ── Metadata ─────────────────────────────────────────────────────────────

    @Test
    void supportedTypes_containsAllCategoryTypes() {
        assertThat(handler.supportedTypes())
                .containsExactlyInAnyOrder("CREATE_CATEGORY", "TOGGLE_CATEGORY", "RENAME_CATEGORY");
    }
}
