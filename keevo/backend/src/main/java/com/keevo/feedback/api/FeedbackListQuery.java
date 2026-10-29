package com.keevo.feedback.api;

/**
 * Filters of the SUPER_ADMIN inbox (Story 12.2e); ANDed, newest first.
 *
 * @param page          zero-based page index
 * @param size          requested page size (capped by the feedback module)
 * @param priority      optional filter (HIGH | NORMAL)
 * @param screenContext optional filter (exact screen route)
 * @param type          optional filter (feedback type, case-insensitive)
 * @param handled       true = handled only, false = unhandled only, null = all
 */
public record FeedbackListQuery(int page, int size, String priority, String screenContext,
                                String type, Boolean handled) {}
