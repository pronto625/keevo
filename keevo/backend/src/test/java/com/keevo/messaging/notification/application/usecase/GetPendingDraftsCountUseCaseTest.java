package com.keevo.messaging.notification.application.usecase;

import com.keevo.messaging.notification.domain.port.out.DraftNotificationRepository;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * TDD tests for GetPendingDraftsCountUseCase (Story 2.4 — AC10).
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("GetPendingDraftsCountUseCase")
class GetPendingDraftsCountUseCaseTest {

    @Mock private DraftNotificationRepository draftRepository;

    private GetPendingDraftsCountUseCase useCase;

    @BeforeEach
    void setUp() {
        useCase = new GetPendingDraftsCountUseCase(draftRepository);
    }

    @Test
    @DisplayName("shouldReturnCountOfUnacknowledgedDrafts")
    void shouldReturnCountOfUnacknowledgedDrafts() {
        when(draftRepository.countPending()).thenReturn(3L);

        long result = useCase.execute("OWNER");

        assertEquals(3L, result);
    }

    @Test
    @DisplayName("shouldThrowForbiddenWhenEmployeeQueries")
    void shouldThrowForbiddenWhenEmployeeQueries() {
        var ex = assertThrows(DomainException.class, () -> useCase.execute("EMPLOYEE"));

        assertEquals(ErrorCode.FORBIDDEN.name(), ex.getDomainCode());
        verifyNoInteractions(draftRepository);
    }

    @Test
    @DisplayName("shouldReturnZeroWhenNoPendingDrafts")
    void shouldReturnZeroWhenNoPendingDrafts() {
        when(draftRepository.countPending()).thenReturn(0L);

        long result = useCase.execute("OWNER");

        assertEquals(0L, result);
    }
}
