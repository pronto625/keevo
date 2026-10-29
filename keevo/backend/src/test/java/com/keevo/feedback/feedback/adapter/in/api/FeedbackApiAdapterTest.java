package com.keevo.feedback.feedback.adapter.in.api;

import com.keevo.feedback.api.FeedbackListQuery;
import com.keevo.feedback.api.FeedbackView;
import com.keevo.feedback.api.SubmitFeedbackInput;
import com.keevo.feedback.feedback.domain.model.Feedback;
import com.keevo.feedback.feedback.domain.port.in.HandleFeedbackUseCase;
import com.keevo.feedback.feedback.domain.port.in.ListFeedbackUseCase;
import com.keevo.feedback.feedback.domain.port.in.ListFeedbackUseCase.ListFeedbackQuery;
import com.keevo.feedback.feedback.domain.port.in.SubmitFeedbackUseCase;
import com.keevo.feedback.feedback.domain.port.in.SubmitFeedbackUseCase.SubmitFeedbackCommand;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import com.keevo.shared.infrastructure.persistence.TenantContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Story 12.2e — published feedback ports. */
@ExtendWith(MockitoExtension.class)
class FeedbackApiAdapterTest {

    @Mock SubmitFeedbackUseCase submit;
    @Mock ListFeedbackUseCase list;
    @Mock HandleFeedbackUseCase handle;

    private FeedbackApiAdapter adapter;

    private static final UUID USER = UUID.randomUUID();
    private static final Instant AT = Instant.parse("2026-10-12T09:00:00Z");

    @BeforeEach
    void setUp() {
        adapter = new FeedbackApiAdapter(submit, list, handle);
    }

    private static Feedback feedback(UUID id, Instant handledAt, UUID handledBy) {
        return new Feedback(id, "BUG", "Le scanner plante", "kv_abc123", USER,
                "1.4.2", "android", "/pos", AT, "HIGH", handledAt, handledBy);
    }

    @Test
    void submit_delegatesSameCommandAndReturnsId() {
        UUID id = UUID.randomUUID();
        var command = new SubmitFeedbackCommand("BUG", "Le scanner plante", "1.4.2", "android", "/pos",
                "kv_abc123", USER);
        when(submit.submit(command)).thenReturn(feedback(id, null, null));

        UUID result = adapter.submit(new SubmitFeedbackInput("BUG", "Le scanner plante", "1.4.2", "android",
                "/pos", "kv_abc123", USER));

        assertThat(result).isEqualTo(id);
    }

    @Test
    void list_mapsEveryFieldAndKeepsPaging() {
        UUID id = UUID.randomUUID();
        UUID admin = UUID.randomUUID();
        Instant handledAt = AT.plusSeconds(60);
        Page<Feedback> page = new PageImpl<>(List.of(feedback(id, handledAt, admin)), PageRequest.of(2, 10), 21);
        when(list.list(new ListFeedbackQuery(2, 10, "HIGH", "/pos", "BUG", null))).thenReturn(page);

        Page<FeedbackView> result = adapter.list(new FeedbackListQuery(2, 10, "HIGH", "/pos", "BUG", null));

        assertThat(result.getNumber()).isEqualTo(2);
        assertThat(result.getSize()).isEqualTo(10);
        assertThat(result.getTotalElements()).isEqualTo(21);
        assertThat(result.getContent()).containsExactly(new FeedbackView(id, "BUG", "Le scanner plante",
                "kv_abc123", USER, "1.4.2", "android", "/pos", AT, "HIGH", handledAt, admin));
        assertThat(result.getContent().get(0).handled()).isTrue();
    }

    @Test
    void count_delegatesFilters() {
        when(list.count(new ListFeedbackQuery(0, 1, "HIGH", null, null, false))).thenReturn(7L);

        assertThat(adapter.count(new FeedbackListQuery(0, 1, "HIGH", null, null, false))).isEqualTo(7L);
    }

    @Test
    void markHandledAndUnhandled_returnViews() {
        UUID id = UUID.randomUUID();
        UUID admin = UUID.randomUUID();
        when(handle.markHandled(id, admin)).thenReturn(feedback(id, AT, admin));
        when(handle.markUnhandled(id, admin)).thenReturn(feedback(id, null, null));

        assertThat(adapter.markHandled(id, admin).handled()).isTrue();
        assertThat(adapter.markUnhandled(id, admin).handled()).isFalse();
    }

    @Test
    void markHandled_unknownIdPropagatesNotFound() {
        UUID id = UUID.randomUUID();
        when(handle.markHandled(id, USER)).thenThrow(new DomainException(ErrorCode.NOT_FOUND, "gone"));

        assertThatThrownBy(() -> adapter.markHandled(id, USER))
                .isInstanceOf(DomainException.class)
                .extracting(e -> ((DomainException) e).getErrorCode())
                .isEqualTo(ErrorCode.NOT_FOUND);
    }

    @Test
    void submitInput_requiresTenantAndUser() {
        assertThatThrownBy(() -> new SubmitFeedbackInput("BUG", "d", null, null, null, " ", USER))
                .isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> new SubmitFeedbackInput("BUG", "d", null, null, null, "kv_abc123", null))
                .isInstanceOf(DomainException.class)
                .extracting(e -> ((DomainException) e).getErrorCode())
                .isEqualTo(ErrorCode.VALIDATION_ERROR);
    }

    @Test
    void submit_boundTenant_mustMatchInputTenant() {
        TenantContext.setCurrentTenant("kv_other1");
        try {
            assertThatThrownBy(() -> adapter.submit(new SubmitFeedbackInput("BUG", "d", null, null, null,
                    "kv_abc123", USER))).isInstanceOf(IllegalStateException.class);
            verifyNoInteractions(submit);
        } finally {
            TenantContext.clear();
        }
    }

    @Test
    void submit_boundTenantEqualToInput_isAccepted() {
        UUID id = UUID.randomUUID();
        when(submit.submit(org.mockito.ArgumentMatchers.any())).thenReturn(feedback(id, null, null));
        TenantContext.setCurrentTenant("kv_abc123");
        try {
            assertThat(adapter.submit(new SubmitFeedbackInput("BUG", "d", null, null, null, "kv_abc123", USER)))
                    .isEqualTo(id);
        } finally {
            TenantContext.clear();
        }
    }
}
