package com.keevo.feedback.feedback.application.service;

import com.keevo.feedback.feedback.domain.model.Feedback;
import com.keevo.feedback.feedback.domain.port.out.FeedbackRepository;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** Spec 9-1 completion — « Traité » flag of the feedback inbox. */
@ExtendWith(MockitoExtension.class)
class HandleFeedbackServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-13T10:00:00Z");

    @Mock private FeedbackRepository feedbackRepository;

    private HandleFeedbackService service() {
        return new HandleFeedbackService(feedbackRepository, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static Feedback feedback(UUID id, Instant handledAt, UUID by) {
        return new Feedback(id, "BUG", "Le scanner plante", "kv_abc123", UUID.randomUUID(),
                "1.0", "android", "/pos", NOW, "HIGH", handledAt, by);
    }

    @Test
    void markHandled_stampsNowAndAdmin() {
        UUID id = UUID.randomUUID();
        UUID admin = UUID.randomUUID();
        when(feedbackRepository.findById(id)).thenReturn(Optional.of(feedback(id, null, null)));
        when(feedbackRepository.setHandled(id, NOW, admin)).thenReturn(Optional.of(feedback(id, NOW, admin)));

        Feedback result = service().markHandled(id, admin);

        assertThat(result.isHandled()).isTrue();
        assertThat(result.getHandledBy()).isEqualTo(admin);
    }

    @Test
    void markHandled_alreadyHandled_keepsFirstHandler() {
        UUID id = UUID.randomUUID();
        UUID first = UUID.randomUUID();
        Instant firstAt = Instant.parse("2026-10-10T08:00:00Z");
        when(feedbackRepository.findById(id)).thenReturn(Optional.of(feedback(id, firstAt, first)));

        Feedback result = service().markHandled(id, UUID.randomUUID());

        assertThat(result.getHandledBy()).isEqualTo(first);
        assertThat(result.getHandledAt()).isEqualTo(firstAt);
        verify(feedbackRepository, never()).setHandled(any(), any(), any());
    }

    @Test
    void markUnhandled_clearsBothColumns() {
        UUID id = UUID.randomUUID();
        when(feedbackRepository.findById(id)).thenReturn(Optional.of(feedback(id, NOW, UUID.randomUUID())));
        when(feedbackRepository.setHandled(id, null, null)).thenReturn(Optional.of(feedback(id, null, null)));

        assertThat(service().markUnhandled(id, UUID.randomUUID()).isHandled()).isFalse();
    }

    @Test
    void markUnhandled_notHandled_isNoOp() {
        UUID id = UUID.randomUUID();
        when(feedbackRepository.findById(id)).thenReturn(Optional.of(feedback(id, null, null)));

        assertThat(service().markUnhandled(id, UUID.randomUUID()).isHandled()).isFalse();
        verify(feedbackRepository, never()).setHandled(any(), any(), any());
    }

    @Test
    void unknownId_isNotFound() {
        UUID id = UUID.randomUUID();
        when(feedbackRepository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().markHandled(id, UUID.randomUUID()))
                .isInstanceOf(DomainException.class)
                .extracting(e -> ((DomainException) e).getErrorCode()).isEqualTo(ErrorCode.NOT_FOUND);
        assertThatThrownBy(() -> service().markUnhandled(id, UUID.randomUUID()))
                .isInstanceOf(DomainException.class)
                .extracting(e -> ((DomainException) e).getErrorCode()).isEqualTo(ErrorCode.NOT_FOUND);
    }
}
