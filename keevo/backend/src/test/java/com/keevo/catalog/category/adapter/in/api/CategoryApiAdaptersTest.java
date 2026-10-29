package com.keevo.catalog.category.adapter.in.api;

import com.keevo.catalog.api.CategoryView;
import com.keevo.catalog.category.application.usecase.CreateCategoryUseCase;
import com.keevo.catalog.category.application.usecase.RenameCategoryUseCase;
import com.keevo.catalog.category.application.usecase.ToggleCategoryUseCase;
import com.keevo.catalog.category.domain.model.Category;
import com.keevo.catalog.category.domain.port.out.CategoryRepository;
import com.keevo.shared.infrastructure.persistence.TenantContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/** Story 12.2a — the catalog category ports delegate to the repository / use cases and map faithfully. */
@ExtendWith(MockitoExtension.class)
class CategoryApiAdaptersTest {

    // 12.2e: the catalog / crm / inventory / commerce API adapters require an explicit, current tenant schema
    @org.junit.jupiter.api.BeforeEach
    void bindTenantContext12e() {
        com.keevo.shared.infrastructure.persistence.TenantContext.setCurrentTenant(TENANT);
    }

    @org.junit.jupiter.api.AfterEach
    void clearTenantContext12e() {
        com.keevo.shared.infrastructure.persistence.TenantContext.clear();
    }

    @Mock private CategoryRepository categoryRepository;
    @Mock private CreateCategoryUseCase createCategory;
    @Mock private ToggleCategoryUseCase toggleCategory;
    @Mock private RenameCategoryUseCase renameCategory;

    private CategoryQueryAdapter query;
    private CategoryCommandAdapter command;

    private static final UUID ID = UUID.randomUUID();
    private static final UUID PARENT = UUID.randomUUID();
    private static final UUID ACTOR = UUID.randomUUID();
    private static final String TENANT = "kv_test";
    private static final Instant T0 = Instant.parse("2026-03-01T09:00:00Z");

    @BeforeEach
    void setUp() {
        query = new CategoryQueryAdapter(categoryRepository);
        command = new CategoryCommandAdapter(createCategory, toggleCategory, renameCategory);
    }

    private static Category category(boolean active) {
        return new Category(ID, "Boissons", PARENT, active, true, T0, T0);
    }

    @Test
    void findById_mapsEveryField() {
        when(categoryRepository.findById(ID)).thenReturn(Optional.of(category(false)));

        assertThat(query.findById(TenantContext.getCurrentTenant(), ID)).contains(new CategoryView(ID, "Boissons", PARENT, false, true, T0, T0));
    }

    @Test
    void findByNameAndParentId_delegates() {
        when(categoryRepository.findByNameAndParentId("boissons", PARENT)).thenReturn(Optional.of(category(true)));
        when(categoryRepository.findByNameAndParentId("autre", null)).thenReturn(Optional.empty());

        assertThat(query.findByNameAndParentId(TenantContext.getCurrentTenant(), "boissons", PARENT)).map(CategoryView::id).contains(ID);
        assertThat(query.findByNameAndParentId(TenantContext.getCurrentTenant(), "autre", null)).isEmpty();
    }

    @Test
    void createWithId_toggle_rename_delegateToTheUseCases() {
        when(createCategory.createWithId(ID, "Boissons", PARENT, ACTOR, TENANT)).thenReturn(category(true));
        when(toggleCategory.execute(ID, ACTOR, TENANT)).thenReturn(category(false));
        when(renameCategory.execute(ID, new RenameCategoryUseCase.RenameCategoryDto("Jus"), ACTOR, TENANT))
                .thenReturn(new Category(ID, "Jus", PARENT, true, true, T0, T0));

        assertThat(command.createWithId(TENANT, ID, "Boissons", PARENT, ACTOR).isActive()).isTrue();
        assertThat(command.toggleActive(TENANT, ID, ACTOR).isActive()).isFalse();
        assertThat(command.rename(TENANT, ID, "Jus", ACTOR).name()).isEqualTo("Jus");
    }

    // 12.2e: the explicit tenantSchema must be the bound tenant — another or a missing schema fails fast
    @org.junit.jupiter.api.Test
    void tenantGuard_otherOrMissingSchemaFailsFast() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> query.findById("kv_other1", ID))
                .isInstanceOf(IllegalStateException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> query.findById(null, ID))
                .isInstanceOf(IllegalStateException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> command.toggleActive("kv_other1", ID, ACTOR))
                .isInstanceOf(IllegalStateException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> command.toggleActive(null, ID, ACTOR))
                .isInstanceOf(IllegalStateException.class);
        org.mockito.Mockito.verifyNoInteractions(categoryRepository, toggleCategory);
    }
}
