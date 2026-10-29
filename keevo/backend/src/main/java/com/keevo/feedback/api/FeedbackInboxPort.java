package com.keevo.feedback.api;

import org.springframework.data.domain.Page;

import java.util.UUID;

/**
 * The SUPER_ADMIN « Retours utilisateurs » inbox (Story 12.2e, over spec 9-1).
 *
 * <p>Platform-wide, admin-only data: the port does no authorisation itself — every caller must
 * enforce the SUPER_ADMIN role before listing, counting or (un)marking feedback.
 */
public interface FeedbackInboxPort {

    Page<FeedbackView> list(FeedbackListQuery query);

    /** Number of feedback matching the filters of {@code query} (paging ignored). */
    long count(FeedbackListQuery query);

    /**
     * SUPER_ADMIN only (enforced by the caller). Idempotent: an already handled feedback keeps its
     * first handler and date.
     *
     * @throws com.keevo.shared.domain.exception.DomainException NOT_FOUND for an unknown id
     */
    FeedbackView markHandled(UUID feedbackId, UUID adminId);

    /**
     * SUPER_ADMIN only (enforced by the caller). Clears the flag; no-op when not handled.
     *
     * @throws com.keevo.shared.domain.exception.DomainException NOT_FOUND for an unknown id
     */
    FeedbackView markUnhandled(UUID feedbackId, UUID adminId);
}
