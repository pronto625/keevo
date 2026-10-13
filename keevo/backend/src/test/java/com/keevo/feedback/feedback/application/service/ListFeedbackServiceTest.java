package com.keevo.feedback.feedback.application.service;

import com.keevo.feedback.feedback.domain.model.Feedback;
import com.keevo.feedback.feedback.domain.port.in.ListFeedbackUseCase.ListFeedbackQuery;
import com.keevo.feedback.feedback.domain.port.out.FeedbackRepository;
import com.keevo.feedback.feedback.domain.port.out.FeedbackRepository.FeedbackCriteria;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/** v1s-15-9: feedback listing moved out of AdminFeedbackController; spec 9-1: ANDed filters. */
@ExtendWith(MockitoExtension.class)
class ListFeedbackServiceTest {

    private static final Sort NEWEST = Sort.by(Sort.Direction.DESC, "submittedAt");

    @Mock private FeedbackRepository feedbackRepository;

    private ListFeedbackService service;
    private final Page<Feedback> empty = new PageImpl<>(List.of());

    @BeforeEach
    void setUp() {
        service = new ListFeedbackService(feedbackRepository);
    }

    @Test
    void list_noFilter_searchesEverythingNewestFirst() {
        when(feedbackRepository.search(new FeedbackCriteria(null, null, null, null), PageRequest.of(2, 20, NEWEST)))
                .thenReturn(empty);

        assertThat(service.list(new ListFeedbackQuery(2, 20, null, null))).isSameAs(empty);
    }

    @Test
    void list_capsSizeAt100() {
        when(feedbackRepository.search(any(), eq(PageRequest.of(0, 100, NEWEST)))).thenReturn(empty);

        assertThat(service.list(new ListFeedbackQuery(0, 10_000, null, null))).isSameAs(empty);
    }

    @Test
    void list_filtersAreAnded() {
        when(feedbackRepository.search(new FeedbackCriteria("HIGH", "/pos", "BUG", false), PageRequest.of(0, 20, NEWEST)))
                .thenReturn(empty);

        assertThat(service.list(new ListFeedbackQuery(0, 20, "HIGH", "/pos", "BUG", false))).isSameAs(empty);
    }

    @Test
    void list_blankFiltersMeanNoFilter() {
        when(feedbackRepository.search(new FeedbackCriteria(null, null, null, true), PageRequest.of(1, 5, NEWEST)))
                .thenReturn(empty);

        assertThat(service.list(new ListFeedbackQuery(1, 5, " ", "", "  ", true))).isSameAs(empty);
    }

    @Test
    void list_clampsNegativePageAndNonPositiveSize() {
        when(feedbackRepository.search(any(), eq(PageRequest.of(0, 1, NEWEST)))).thenReturn(empty);

        assertThat(service.list(new ListFeedbackQuery(-3, 0, null, null))).isSameAs(empty);
    }

    @Test
    void count_usesTheSameCriteria() {
        when(feedbackRepository.count(new FeedbackCriteria("HIGH", null, null, false))).thenReturn(4L);

        assertThat(service.count(new ListFeedbackQuery(0, 1, "HIGH", null, null, false))).isEqualTo(4L);
    }
}
