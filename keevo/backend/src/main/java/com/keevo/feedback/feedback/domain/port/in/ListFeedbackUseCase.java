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

    /**
     * @param page          zero-based page index
     * @param size          requested page size (capped by the service)
     * @param priority      optional filter; takes precedence over {@code screenContext}
     * @param screenContext optional filter, applied when {@code priority} is absent
     */
    record ListFeedbackQuery(int page, int size, String priority, String screenContext) {}
}
