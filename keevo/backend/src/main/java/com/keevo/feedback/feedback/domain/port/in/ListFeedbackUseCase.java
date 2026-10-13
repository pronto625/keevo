package com.keevo.feedback.feedback.domain.port.in;

import com.keevo.feedback.feedback.domain.model.Feedback;
import org.springframework.data.domain.Page;

/**
 * ListFeedbackUseCase — Inbound port for the SUPER_ADMIN feedback listing (Story 14.5, FR92).
 *
 * <p>v1s-15-9: the admin controller no longer reaches the repository directly.
 */
public interface ListFeedbackUseCase {

    Page<Feedback> list(ListFeedbackQuery query);

    /** Number of feedback matching the filters of {@code query} (paging ignored) — sidebar badge. */
    long count(ListFeedbackQuery query);

    /**
     * Filters are ANDed (spec 9-1 completion); results are newest first.
     *
     * @param page          zero-based page index
     * @param size          requested page size (capped by the service)
     * @param priority      optional filter (HIGH | NORMAL)
     * @param screenContext optional filter (exact screen route)
     * @param type          optional filter (feedback type, case-insensitive)
     * @param handled       true = handled only, false = unhandled only, null = all
     */
    record ListFeedbackQuery(int page, int size, String priority, String screenContext,
                             String type, Boolean handled) {

        /** Unfiltered on type and handled state. */
        public ListFeedbackQuery(int page, int size, String priority, String screenContext) {
            this(page, size, priority, screenContext, null, null);
        }
    }
}
