package com.keevo.feedback.feedback.application.service;

import com.keevo.feedback.feedback.domain.model.Feedback;
import com.keevo.feedback.feedback.domain.port.in.ListFeedbackUseCase.ListFeedbackQuery;
import com.keevo.feedback.feedback.domain.port.out.FeedbackRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/** v1s-15-9: feedback listing moved out of AdminFeedbackController. */
@ExtendWith(MockitoExtension.class)
class ListFeedbackServiceTest {

    @Mock private FeedbackRepository feedbackRepository;

    private ListFeedbackService service;
    private final Page<Feedback> empty = new PageImpl<>(List.of());

    @BeforeEach
    void setUp() {
        service = new ListFeedbackService(feedbackRepository);
    }

    @Test
    void list_noFilter_findsAllWithRequestedPage() {
        when(feedbackRepository.findAll(PageRequest.of(2, 20))).thenReturn(empty);

        assertThat(service.list(new ListFeedbackQuery(2, 20, null, null))).isSameAs(empty);
    }

    @Test
    void list_capsSizeAt100() {
        when(feedbackRepository.findAll(PageRequest.of(0, 100))).thenReturn(empty);

        assertThat(service.list(new ListFeedbackQuery(0, 10_000, null, null))).isSameAs(empty);
    }

    @Test
    void list_priorityTakesPrecedenceOverScreenContext() {
        when(feedbackRepository.findByPriority("HIGH", PageRequest.of(0, 20))).thenReturn(empty);

        assertThat(service.list(new ListFeedbackQuery(0, 20, "HIGH", "/pos"))).isSameAs(empty);
        verify(feedbackRepository, never()).findByScreenContext(any(), any());
        verify(feedbackRepository, never()).findAll(any(org.springframework.data.domain.Pageable.class));
    }

    @Test
    void list_screenContextFilter() {
        when(feedbackRepository.findByScreenContext("/pos", PageRequest.of(1, 5))).thenReturn(empty);

        assertThat(service.list(new ListFeedbackQuery(1, 5, null, "/pos"))).isSameAs(empty);
    }

    @Test
    void list_clampsNegativePageAndNonPositiveSize() {
        when(feedbackRepository.findAll(PageRequest.of(0, 1))).thenReturn(empty);

        assertThat(service.list(new ListFeedbackQuery(-3, 0, null, null))).isSameAs(empty);
    }
}
