package com.keevo.messaging.notification.application.listener;

import com.keevo.messaging.notification.domain.model.DraftPendingValidation;
import com.keevo.messaging.notification.domain.port.out.DraftNotificationRepository;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import com.keevo.shared.event.catalog.product.DraftProductPendingEvent;
import com.keevo.shared.event.catalog.product.DraftProductValidatedEvent;
import com.keevo.shared.infrastructure.persistence.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.event.EventListener;
import org.springframework.transaction.event.TransactionalEventListener;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Story 12.6a — draft-validation tracking driven by the catalogue draft events (formerly the 12.2e ports). */
@ExtendWith(MockitoExtension.class)
class DraftValidationTrackingListenerTest {

    private static final String TENANT = "kv_test01";

    @Mock DraftNotificationRepository repository;

    private DraftValidationTrackingListener listener;

    @BeforeEach
    void setUp() {
        TenantContext.setCurrentTenant(TENANT);
        listener = new DraftValidationTrackingListener(repository);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void listenersAreSynchronous_joinThePublisherTransaction() throws NoSuchMethodException {
        for (var method : new java.lang.reflect.Method[] {
                DraftValidationTrackingListener.class.getMethod("onDraftPending", DraftProductPendingEvent.class),
                DraftValidationTrackingListener.class.getMethod("onDraftValidated", DraftProductValidatedEvent.class)}) {
            assertThat(method.isAnnotationPresent(EventListener.class)).isTrue();
            assertThat(method.isAnnotationPresent(TransactionalEventListener.class)).isFalse();
            assertThat(method.isAnnotationPresent(org.springframework.scheduling.annotation.Async.class)).isFalse();
        }
    }

    @Test
    void draftPending_savesUnacknowledgedDraftWithTenantDerivedUuid() {
        UUID productId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();

        listener.onDraftPending(new DraftProductPendingEvent(productId, "Article", actorId, TENANT, Instant.now()));

        ArgumentCaptor<DraftPendingValidation> saved = ArgumentCaptor.forClass(DraftPendingValidation.class);
        verify(repository).save(saved.capture());
        assertThat(saved.getValue().getProductId()).isEqualTo(productId);
        assertThat(saved.getValue().getProductName()).isEqualTo("Article");
        assertThat(saved.getValue().getActorId()).isEqualTo(actorId);
        assertThat(saved.getValue().getTenantId())
                .isEqualTo(UUID.nameUUIDFromBytes(TENANT.getBytes(StandardCharsets.UTF_8)));
        assertThat(saved.getValue().isAcknowledged()).isFalse();
    }

    @Test
    void draftValidated_marksExistingDraft() {
        UUID productId = UUID.randomUUID();
        var draft = DraftPendingValidation.create(productId, "Article", UUID.randomUUID(), UUID.randomUUID());
        when(repository.findByProductId(productId)).thenReturn(Optional.of(draft));

        listener.onDraftValidated(new DraftProductValidatedEvent(productId, UUID.randomUUID(), TENANT, Instant.now()));

        assertThat(draft.isAcknowledged()).isTrue();
        verify(repository).saveUpdated(draft);
    }

    @Test
    void draftValidated_noPendingDraft_isNoOp() {
        UUID productId = UUID.randomUUID();
        when(repository.findByProductId(productId)).thenReturn(Optional.empty());

        listener.onDraftValidated(new DraftProductValidatedEvent(productId, null, TENANT, Instant.now()));

        verify(repository, never()).saveUpdated(any());
    }

    @Test
    void tenantGuard_otherOrMissingSchemaFailsFast() {
        assertThatThrownBy(() -> listener.onDraftValidated(
                new DraftProductValidatedEvent(UUID.randomUUID(), null, "kv_other1", Instant.now())))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> listener.onDraftPending(
                new DraftProductPendingEvent(UUID.randomUUID(), "A", UUID.randomUUID(), "kv_other1", Instant.now())))
                .isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(repository);
    }

    @Test
    void draftEvents_requireProductAndTenant() {
        UUID id = UUID.randomUUID();
        for (org.assertj.core.api.ThrowableAssert.ThrowingCallable invalid : List.<org.assertj.core.api.ThrowableAssert.ThrowingCallable>of(
                () -> new DraftProductPendingEvent(null, "A", id, TENANT, Instant.now()),
                () -> new DraftProductPendingEvent(id, "A", id, null, Instant.now()),
                () -> new DraftProductPendingEvent(id, "A", id, " ", Instant.now()),
                () -> new DraftProductValidatedEvent(null, id, TENANT, Instant.now()),
                () -> new DraftProductValidatedEvent(id, id, null, Instant.now()))) {
            assertThatThrownBy(invalid)
                    .isInstanceOf(DomainException.class)
                    .extracting(e -> ((DomainException) e).getErrorCode())
                    .isEqualTo(ErrorCode.VALIDATION_ERROR);
        }
    }
}
