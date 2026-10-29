package com.keevo.messaging.notification.adapter.in.api;

import com.keevo.messaging.api.PendingDraftInput;
import com.keevo.messaging.notification.domain.model.DraftPendingValidation;
import com.keevo.messaging.notification.domain.port.out.DraftNotificationRepository;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import com.keevo.shared.infrastructure.persistence.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Story 12.2e — published draft-validation ports of messaging. */
@ExtendWith(MockitoExtension.class)
class DraftValidationApiAdapterTest {

    private static final String TENANT = "kv_test01";

    @Mock DraftNotificationRepository repository;

    private DraftValidationApiAdapter adapter;

    @BeforeEach
    void setUp() {
        TenantContext.setCurrentTenant(TENANT);
        adapter = new DraftValidationApiAdapter(repository);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void recordPending_savesUnacknowledgedDraftWithTenantDerivedUuid() {
        UUID productId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();

        adapter.recordPending(TENANT, new PendingDraftInput(productId, "Article", actorId));

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
    void acknowledge_marksExistingDraft() {
        UUID productId = UUID.randomUUID();
        var draft = DraftPendingValidation.create(productId, "Article", UUID.randomUUID(), UUID.randomUUID());
        when(repository.findByProductId(productId)).thenReturn(Optional.of(draft));

        adapter.acknowledge(TENANT, productId);

        assertThat(draft.isAcknowledged()).isTrue();
        verify(repository).saveUpdated(draft);
    }

    @Test
    void acknowledge_noPendingDraft_isNoOp() {
        UUID productId = UUID.randomUUID();
        when(repository.findByProductId(productId)).thenReturn(Optional.empty());

        adapter.acknowledge(TENANT, productId);

        verify(repository, never()).saveUpdated(any());
    }

    @Test
    void countPending_returnsTheRepositoryCount() {
        when(repository.countPending()).thenReturn(4L);

        assertThat(adapter.countPending(TENANT)).isEqualTo(4L);
    }

    @Test
    void tenantGuard_otherOrMissingSchemaFailsFast() {
        assertThatThrownBy(() -> adapter.countPending("kv_other1"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> adapter.acknowledge(null, UUID.randomUUID()))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> adapter.recordPending("kv_other1",
                new PendingDraftInput(UUID.randomUUID(), "A", UUID.randomUUID())))
                .isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(repository);
    }

    @Test
    void pendingDraftInput_requiresProductId() {
        assertThatThrownBy(() -> new PendingDraftInput(null, "A", UUID.randomUUID()))
                .isInstanceOf(DomainException.class)
                .extracting(e -> ((DomainException) e).getErrorCode())
                .isEqualTo(ErrorCode.VALIDATION_ERROR);
    }
}
