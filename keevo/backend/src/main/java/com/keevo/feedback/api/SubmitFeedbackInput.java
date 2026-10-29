package com.keevo.feedback.api;

import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;

import java.util.UUID;

/**
 * A feedback submitted by a user of {@code tenantSchema} (Story 12.2e — the sync push). The type and
 * description rules stay those of the feedback use case.
 */
public record SubmitFeedbackInput(String type, String description, String appVersion, String platform,
                                  String screenContext, String tenantSchema, UUID userId) {
    public SubmitFeedbackInput {
        if (tenantSchema == null || tenantSchema.isBlank()) {
            throw new DomainException(ErrorCode.VALIDATION_ERROR, "Tenant schema is required");
        }
        if (userId == null) {
            throw new DomainException(ErrorCode.VALIDATION_ERROR, "User id is required");
        }
    }
}
