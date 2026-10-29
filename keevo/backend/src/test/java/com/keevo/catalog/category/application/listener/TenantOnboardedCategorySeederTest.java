package com.keevo.catalog.category.application.listener;

import com.keevo.catalog.category.domain.model.Category;
import com.keevo.catalog.category.domain.port.out.CategoryRepository;
import com.keevo.shared.event.catalog.onboarding.SectorKind;
import com.keevo.shared.event.catalog.onboarding.TenantOnboardedEvent;
import com.keevo.shared.infrastructure.persistence.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.*;

/** Story 12.6a — onboarding template categories seeded by the catalog from {@link TenantOnboardedEvent}. */
@ExtendWith(MockitoExtension.class)
class TenantOnboardedCategorySeederTest {

    private static final String TENANT = "kv_test";
    private static final UUID OWNER = UUID.randomUUID();

    @Mock private CategoryRepository categoryRepository;

    private TenantOnboardedCategorySeeder seeder;

    @BeforeEach
    void setUp() {
        TenantContext.setCurrentTenant(TENANT);
        seeder = new TenantOnboardedCategorySeeder(categoryRepository);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private static TenantOnboardedEvent event(List<String> names) {
        return new TenantOnboardedEvent(TENANT, SectorKind.CLOTHING, names, OWNER, Instant.now());
    }

    @Test
    void runsBeforeCommitInTheOnboardingTransaction() throws NoSuchMethodException {
        TransactionalEventListener annotation = TenantOnboardedCategorySeeder.class
                .getMethod("onTenantOnboarded", TenantOnboardedEvent.class)
                .getAnnotation(TransactionalEventListener.class);
        assertThat(annotation).isNotNull();
        assertThat(annotation.phase()).isEqualTo(TransactionPhase.BEFORE_COMMIT);
    }

    @Test
    @SuppressWarnings("unchecked")
    void persistsActiveRootTemplateCategoriesWithGeneratedIds() {
        when(categoryRepository.findExistingRootNamesIgnoreCase(anyCollection())).thenReturn(Set.of());
        when(categoryRepository.saveAll(anyList())).thenAnswer(inv -> inv.getArgument(0));

        seeder.onTenantOnboarded(event(List.of("Robes", "Pagnes", "Chaussures")));

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
    void skipsNullAndBlankNames() {
        when(categoryRepository.findExistingRootNamesIgnoreCase(anyCollection())).thenReturn(Set.of());
        when(categoryRepository.saveAll(anyList())).thenAnswer(inv -> inv.getArgument(0));
        List<String> names = new ArrayList<>(List.of("Robes", " "));
        names.add(null);
        names.add("Pagnes");

        seeder.onTenantOnboarded(event(names));

        ArgumentCaptor<List<Category>> captor = ArgumentCaptor.forClass(List.class);
        verify(categoryRepository).saveAll(captor.capture());
        assertThat(captor.getValue()).extracting(Category::name).containsExactly("Robes", "Pagnes");
    }

    @Test
    void replay_existingTemplateCategoriesAreNotDuplicated_withASingleExistenceQuery() {
        when(categoryRepository.findExistingRootNamesIgnoreCase(anyCollection())).thenReturn(Set.of("robes", "pagnes"));

        seeder.onTenantOnboarded(event(List.of("Robes", "Pagnes")));

        verify(categoryRepository, times(1)).findExistingRootNamesIgnoreCase(anyCollection());
        verify(categoryRepository, never()).saveAll(any());
        verify(categoryRepository, never()).findByNameAndParentId(any(), any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void duplicatesWithinTheTemplate_caseVariants_seededOnce_firstSpellingKept() {
        when(categoryRepository.findExistingRootNamesIgnoreCase(anyCollection())).thenReturn(Set.of("pagnes"));
        when(categoryRepository.saveAll(anyList())).thenAnswer(inv -> inv.getArgument(0));

        seeder.onTenantOnboarded(event(List.of("Robes", "robes", " ROBES ", "Pagnes", "Chaussures", "Robes")));

        ArgumentCaptor<java.util.Collection<String>> asked = ArgumentCaptor.forClass(java.util.Collection.class);
        verify(categoryRepository).findExistingRootNamesIgnoreCase(asked.capture());
        assertThat(asked.getValue()).containsExactly("Robes", "Pagnes", "Chaussures");
        ArgumentCaptor<List<Category>> captor = ArgumentCaptor.forClass(List.class);
        verify(categoryRepository).saveAll(captor.capture());
        assertThat(captor.getValue()).extracting(Category::name).containsExactly("Robes", "Chaussures");
    }

    @Test
    void publishedWithoutATransaction_stillRuns() throws NoSuchMethodException {
        TransactionalEventListener annotation = TenantOnboardedCategorySeeder.class
                .getMethod("onTenantOnboarded", TenantOnboardedEvent.class)
                .getAnnotation(TransactionalEventListener.class);
        assertThat(annotation.fallbackExecution()).isTrue();
    }

    @Test
    void eventCleansTemplateNames_nullBlankDropped_trimmed() {
        List<String> names = new ArrayList<>(List.of(" Robes ", " "));
        names.add(null);
        names.add("Pagnes");
        assertThat(event(names).templateCategories()).containsExactly("Robes", "Pagnes");
    }

    @Test
    void emptyTemplate_createsNothing() {
        seeder.onTenantOnboarded(event(null));
        verifyNoInteractions(categoryRepository);
    }

    @Test
    void otherTenantThanTheBoundOne_failsFast() {
        assertThatThrownBy(() -> seeder.onTenantOnboarded(
                new TenantOnboardedEvent("kv_other1", null, List.of("Robes"), OWNER, Instant.now())))
                .isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(categoryRepository);
    }
}
