package com.keevo.catalog.category.adapter.in.api;

import com.keevo.catalog.api.CategoryView;
import com.keevo.catalog.category.application.usecase.CreateCategoryUseCase;
import com.keevo.catalog.category.application.usecase.RenameCategoryUseCase;
import com.keevo.catalog.category.application.usecase.ToggleCategoryUseCase;
import com.keevo.catalog.category.domain.model.Category;
import com.keevo.catalog.category.domain.port.out.CategoryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

/** Story 12.2a — the catalog category ports delegate to the repository / use cases and map faithfully. */
@ExtendWith(MockitoExtension.class)
class CategoryApiAdaptersTest {

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
        command = new CategoryCommandAdapter(createCategory, toggleCategory, renameCategory, categoryRepository);
    }

    private static Category category(boolean active) {
        return new Category(ID, "Boissons", PARENT, active, true, T0, T0);
    }

    @Test
    void findById_mapsEveryField() {
        when(categoryRepository.findById(ID)).thenReturn(Optional.of(category(false)));

        assertThat(query.findById(ID)).contains(new CategoryView(ID, "Boissons", PARENT, false, true, T0, T0));
    }

    @Test
    void findByNameAndParentId_delegates() {
        when(categoryRepository.findByNameAndParentId("boissons", PARENT)).thenReturn(Optional.of(category(true)));
        when(categoryRepository.findByNameAndParentId("autre", null)).thenReturn(Optional.empty());

        assertThat(query.findByNameAndParentId("boissons", PARENT)).map(CategoryView::id).contains(ID);
        assertThat(query.findByNameAndParentId("autre", null)).isEmpty();
    }

    @Test
    void createWithId_toggle_rename_delegateToTheUseCases() {
        when(createCategory.createWithId(ID, "Boissons", PARENT, ACTOR, TENANT)).thenReturn(category(true));
        when(toggleCategory.execute(ID, ACTOR, TENANT)).thenReturn(category(false));
        when(renameCategory.execute(ID, new RenameCategoryUseCase.RenameCategoryDto("Jus"), ACTOR, TENANT))
                .thenReturn(new Category(ID, "Jus", PARENT, true, true, T0, T0));

        assertThat(command.createWithId(ID, "Boissons", PARENT, ACTOR, TENANT).isActive()).isTrue();
        assertThat(command.toggleActive(ID, ACTOR, TENANT).isActive()).isFalse();
        assertThat(command.rename(ID, "Jus", ACTOR, TENANT).name()).isEqualTo("Jus");
    }

    @Test
    @SuppressWarnings("unchecked")
    void seedTemplateCategories_persistsActiveRootTemplateCategoriesWithGeneratedIds() {
        when(categoryRepository.saveAll(anyList())).thenAnswer(inv -> inv.getArgument(0));

        int created = command.seedTemplateCategories(List.of("Robes", "Pagnes", "Chaussures"));

        assertThat(created).isEqualTo(3);
        ArgumentCaptor<List<Category>> captor = ArgumentCaptor.forClass(List.class);
        verify(categoryRepository).saveAll(captor.capture());
        List<Category> seeded = captor.getValue();
        assertThat(seeded).extracting(Category::name).containsExactly("Robes", "Pagnes", "Chaussures");
        // ids deliberately null so Hibernate persists (not merges) and generates them
        assertThat(seeded).allSatisfy(c -> {
            assertThat(c.id()).isNull();
            assertThat(c.parentId()).isNull();
            assertThat(c.isActive()).isTrue();
            assertThat(c.isCustom()).isFalse();
            assertThat(c.createdAt()).isNotNull();
        });
    }

    @Test
    @SuppressWarnings("unchecked")
    void seedTemplateCategories_skipsNullAndBlankNames() {
        when(categoryRepository.saveAll(anyList())).thenAnswer(inv -> inv.getArgument(0));
        List<String> names = new java.util.ArrayList<>(List.of("Robes", " "));
        names.add(null);
        names.add("Pagnes");

        int created = command.seedTemplateCategories(names);

        assertThat(created).isEqualTo(2);
        ArgumentCaptor<List<Category>> captor = ArgumentCaptor.forClass(List.class);
        verify(categoryRepository).saveAll(captor.capture());
        assertThat(captor.getValue()).extracting(Category::name).containsExactly("Robes", "Pagnes");
    }

    @Test
    void seedTemplateCategories_nullList_createsNothing() {
        assertThat(command.seedTemplateCategories(null)).isZero();
        verifyNoInteractions(categoryRepository);
    }
}
