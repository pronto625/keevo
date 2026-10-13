package com.keevo.feedback.feedback.application.service;

import com.keevo.feedback.feedback.domain.model.Feedback;
import com.keevo.feedback.feedback.domain.port.in.ListFeedbackUseCase;
import com.keevo.feedback.feedback.domain.port.out.FeedbackRepository;
import com.keevo.feedback.feedback.domain.port.out.FeedbackRepository.FeedbackCriteria;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * ListFeedbackService — paginated feedback listing for SUPER_ADMIN (Story 14.5, FR92; v1s-15-9).
 *
 * <p>Page is clamped to &ge; 0 and size to [1, {@value #MAX_PAGE_SIZE}]. Filters (priority,
 * screenContext, type, handled) are ANDed; blank strings mean "no filter". Newest first.
 */
@Service
@Transactional(readOnly = true)
public class ListFeedbackService implements ListFeedbackUseCase {

    static final int MAX_PAGE_SIZE = 100;
    static final Sort NEWEST_FIRST = Sort.by(Sort.Direction.DESC, "submittedAt");

    private final FeedbackRepository feedbackRepository;

    public ListFeedbackService(FeedbackRepository feedbackRepository) {
        this.feedbackRepository = feedbackRepository;
    }

    @Override
    public Page<Feedback> list(ListFeedbackQuery query) {
        // Clamped: a negative page or a size < 1 would make PageRequest.of throw (→ 500).
        int page = Math.max(0, query.page());
        int size = Math.max(1, Math.min(query.size(), MAX_PAGE_SIZE));
        PageRequest pageable = PageRequest.of(page, size, NEWEST_FIRST);
        return feedbackRepository.search(criteria(query), pageable);
    }

    @Override
    public long count(ListFeedbackQuery query) {
        return feedbackRepository.count(criteria(query));
    }

    private static FeedbackCriteria criteria(ListFeedbackQuery query) {
        return new FeedbackCriteria(
                blankToNull(query.priority()), blankToNull(query.screenContext()),
                blankToNull(query.type()), query.handled());
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
