package com.keevo.catalog.category.application.usecase;

import com.keevo.catalog.category.domain.event.CategoryActiveChangedEvent;
import com.keevo.catalog.category.domain.event.CategoryCreatedEvent;
import com.keevo.catalog.category.domain.event.CategoryRenamedEvent;
import com.keevo.catalog.category.domain.model.Category;
import com.keevo.catalog.category.domain.port.out.CategoryRepository;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * v1s-14-15 — category use cases: CATEGORY_NOT_FOUND on unknown ids and domain events
 * emitted only on an actual state change.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("Category use cases (v1s-14-15)")
class CategoryUseCasesTest {

    @Mock CategoryRepository repository;
    @Mock ApplicationEventPublisher publisher;

    private static final UUID ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID PARENT = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID ACTOR = UUID.randomUUID();
    private static final String TENANT = "kv_test";

    private static Category cat(UUID id, String name, UUID parentId, boolean active) {
        Instant now = Instant.now();
        return new Category(id, name, parentId, active, true, now, now);
    }

    private static void assertCategoryNotFound(Throwable t) {
        assertThat(t).isInstanceOf(DomainException.class);
        assertThat(((DomainException) t).getErrorCode()).isEqualTo(ErrorCode.CATEGORY_NOT_FOUND);
    }

    @Nested
    @DisplayName("CreateCategoryUseCase")
    class Create {
        CreateCategoryUseCase useCase;

        @BeforeEach
        void setUp() {
            useCase = new CreateCategoryUseCase(repository, publisher);
        }

        @Test
        void execute_root_createsAndPublishesCreatedEvent() {
            when(repository.createCustom("Boissons", null)).thenReturn(cat(ID, "Boissons", null, true));

            Category created = useCase.execute(new CreateCategoryUseCase.CreateCategoryDto("Boissons", null), ACTOR, TENANT);

            assertThat(created.id()).isEqualTo(ID);
            var captor = ArgumentCaptor.forClass(CategoryCreatedEvent.class);
            verify(publisher).publishEvent(captor.capture());
            assertThat(captor.getValue().categoryId()).isEqualTo(ID);
            assertThat(captor.getValue().name()).isEqualTo("Boissons");
            assertThat(captor.getValue().parentId()).isNull();
            assertThat(captor.getValue().actorId()).isEqualTo(ACTOR);
            assertThat(captor.getValue().tenantId()).isEqualTo(TENANT);
        }

        @Test
        void execute_unknownParent_throwsCategoryNotFound_nothingSaved_noEvent() {
            when(repository.findById(PARENT)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> useCase.execute(
                    new CreateCategoryUseCase.CreateCategoryDto("Sodas", PARENT), ACTOR, TENANT))
                    .satisfies(CategoryUseCasesTest::assertCategoryNotFound);

            verify(repository, never()).createCustom(any(), any());
            verifyNoInteractions(publisher);
        }

        @Test
        void execute_knownParent_creates() {
            when(repository.findById(PARENT)).thenReturn(Optional.of(cat(PARENT, "Boissons", null, true)));
            when(repository.createCustom("Sodas", PARENT)).thenReturn(cat(ID, "Sodas", PARENT, true));

            useCase.execute(new CreateCategoryUseCase.CreateCategoryDto("Sodas", PARENT), ACTOR, TENANT);

            verify(publisher).publishEvent(any(CategoryCreatedEvent.class));
        }

        @Test
        void createWithId_savesClientUuid_andPublishesEvent() {
            when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            Category saved = useCase.createWithId(ID, "Épicerie", null, ACTOR, TENANT);

            assertThat(saved.id()).isEqualTo(ID);
            assertThat(saved.isActive()).isTrue();
            assertThat(saved.isCustom()).isTrue();
            var captor = ArgumentCaptor.forClass(CategoryCreatedEvent.class);
            verify(publisher).publishEvent(captor.capture());
            assertThat(captor.getValue().categoryId()).isEqualTo(ID);
            assertThat(captor.getValue().actorId()).isEqualTo(ACTOR);
        }
    }

    @Nested
    @DisplayName("ToggleCategoryUseCase")
    class Toggle {
        ToggleCategoryUseCase useCase;

        @BeforeEach
        void setUp() {
            useCase = new ToggleCategoryUseCase(repository, publisher);
        }

        @Test
        void execute_unknownId_throwsCategoryNotFound_noToggle() {
            when(repository.findById(ID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> useCase.execute(ID, ACTOR, TENANT))
                    .satisfies(CategoryUseCasesTest::assertCategoryNotFound);

            verify(repository, never()).toggleActive(any());
            verifyNoInteractions(publisher);
        }

        @Test
        void execute_activeToInactive_publishesDeactivated() {
            when(repository.findById(ID)).thenReturn(Optional.of(cat(ID, "Boissons", null, true)));
            when(repository.toggleActive(ID)).thenReturn(cat(ID, "Boissons", null, false));

            useCase.execute(ID, ACTOR, TENANT);

            var captor = ArgumentCaptor.forClass(CategoryActiveChangedEvent.class);
            verify(publisher).publishEvent(captor.capture());
            assertThat(captor.getValue().active()).isFalse();
            assertThat(captor.getValue().actorId()).isEqualTo(ACTOR);
            assertThat(captor.getValue().tenantId()).isEqualTo(TENANT);
        }

        @Test
        void execute_inactiveToActive_publishesActivated() {
            when(repository.findById(ID)).thenReturn(Optional.of(cat(ID, "Boissons", null, false)));
            when(repository.toggleActive(ID)).thenReturn(cat(ID, "Boissons", null, true));

            useCase.execute(ID, ACTOR, TENANT);

            var captor = ArgumentCaptor.forClass(CategoryActiveChangedEvent.class);
            verify(publisher).publishEvent(captor.capture());
            assertThat(captor.getValue().active()).isTrue();
        }

        @Test
        void deactivate_unknownId_throwsCategoryNotFound() {
            when(repository.findById(ID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> useCase.deactivate(ID, ACTOR, TENANT))
                    .satisfies(CategoryUseCasesTest::assertCategoryNotFound);

            verify(repository, never()).deactivate(any());
            verifyNoInteractions(publisher);
        }

        @Test
        void deactivate_active_deactivatesAndPublishesDeactivated() {
            when(repository.findById(ID)).thenReturn(Optional.of(cat(ID, "Boissons", null, true)));

            useCase.deactivate(ID, ACTOR, TENANT);

            verify(repository).deactivate(ID);
            var captor = ArgumentCaptor.forClass(CategoryActiveChangedEvent.class);
            verify(publisher).publishEvent(captor.capture());
            assertThat(captor.getValue().active()).isFalse();
            assertThat(captor.getValue().name()).isEqualTo("Boissons");
        }

        @Test
        void deactivate_alreadyInactive_isNoOp_noSave_noEvent() {
            when(repository.findById(ID)).thenReturn(Optional.of(cat(ID, "Boissons", null, false)));

            useCase.deactivate(ID, ACTOR, TENANT);

            verify(repository, never()).deactivate(any());
            verify(repository, never()).save(any());
            verifyNoInteractions(publisher);
        }
    }

    @Nested
    @DisplayName("RenameCategoryUseCase")
    class Rename {
        RenameCategoryUseCase useCase;

        @BeforeEach
        void setUp() {
            useCase = new RenameCategoryUseCase(repository, publisher);
        }

        @Test
        void execute_unknownId_throwsCategoryNotFound_noRename() {
            when(repository.findById(ID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> useCase.execute(ID,
                    new RenameCategoryUseCase.RenameCategoryDto("Nouveau"), ACTOR, TENANT))
                    .satisfies(CategoryUseCasesTest::assertCategoryNotFound);

            verify(repository, never()).rename(any(), any());
            verifyNoInteractions(publisher);
        }

        @Test
        void execute_newName_publishesRenamedEvent() {
            when(repository.findById(ID)).thenReturn(Optional.of(cat(ID, "Ancien", null, true)));
            when(repository.rename(ID, "Nouveau")).thenReturn(cat(ID, "Nouveau", null, true));

            Category renamed = useCase.execute(ID, new RenameCategoryUseCase.RenameCategoryDto("Nouveau"), ACTOR, TENANT);

            assertThat(renamed.name()).isEqualTo("Nouveau");
            var captor = ArgumentCaptor.forClass(CategoryRenamedEvent.class);
            verify(publisher).publishEvent(captor.capture());
            assertThat(captor.getValue().oldName()).isEqualTo("Ancien");
            assertThat(captor.getValue().newName()).isEqualTo("Nouveau");
            assertThat(captor.getValue().actorId()).isEqualTo(ACTOR);
        }

        @Test
        void execute_sameName_noEvent() {
            when(repository.findById(ID)).thenReturn(Optional.of(cat(ID, "Boissons", null, true)));
            when(repository.rename(ID, "Boissons")).thenReturn(cat(ID, "Boissons", null, true));

            useCase.execute(ID, new RenameCategoryUseCase.RenameCategoryDto("Boissons"), ACTOR, TENANT);

            verifyNoInteractions(publisher);
        }
    }
}
