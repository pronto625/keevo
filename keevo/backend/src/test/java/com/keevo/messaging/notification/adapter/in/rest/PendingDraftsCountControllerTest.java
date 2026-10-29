package com.keevo.messaging.notification.adapter.in.rest;

import com.keevo.messaging.notification.application.service.PendingDraftsQueryService;
import com.keevo.messaging.notification.domain.port.out.DraftNotificationRepository;
import com.keevo.shared.infrastructure.persistence.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import com.keevo.shared.infrastructure.security.CurrentActor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Story 12.6a — pending-drafts counter moved from the catalog controller, same RBAC and response. */
@ExtendWith(MockitoExtension.class)
class PendingDraftsCountControllerTest {

    @Mock DraftNotificationRepository repository;

    private PendingDraftsCountController controller;

    @BeforeEach
    void setUp() {
        TenantContext.setCurrentTenant("kv_test01");
        controller = new PendingDraftsCountController(new PendingDraftsQueryService(repository));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void owner_getsTheRepositoryCount() {
        when(repository.countPending()).thenReturn(3L);
        try (MockedStatic<CurrentActor> actor = mockStatic(CurrentActor.class)) {
            actor.when(CurrentActor::role).thenReturn("OWNER");

            var response = controller.getPendingDraftsCount();

            assertThat(response.getStatusCode().value()).isEqualTo(200);
            assertThat(response.getBody().data()).isEqualTo(3L);
        }
    }

    @Test
    void employee_forbidden_counterNeverRead() {
        try (MockedStatic<CurrentActor> actor = mockStatic(CurrentActor.class)) {
            actor.when(CurrentActor::role).thenReturn("EMPLOYEE");

            assertThatThrownBy(() -> controller.getPendingDraftsCount())
                    .isInstanceOf(DomainException.class)
                    .extracting(e -> ((DomainException) e).getErrorCode())
                    .isEqualTo(ErrorCode.FORBIDDEN);
            verifyNoInteractions(repository);
        }
    }

    @Test
    void noRole_forbidden() {
        try (MockedStatic<CurrentActor> actor = mockStatic(CurrentActor.class)) {
            actor.when(CurrentActor::role).thenReturn(null);

            assertThatThrownBy(() -> controller.getPendingDraftsCount())
                    .isInstanceOf(DomainException.class);
            verifyNoInteractions(repository);
        }
    }

    @Test
    void queryService_otherOrMissingTenantFailsFast() {
        var service = new PendingDraftsQueryService(repository);
        assertThatThrownBy(() -> service.countPending("kv_other1")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> service.countPending(null)).isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(repository);
    }
}
