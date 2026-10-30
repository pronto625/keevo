package com.keevo.sync.sync.application.handler;

import com.keevo.shared.application.port.sync.AbstractSyncOperationHandler;
import com.keevo.feedback.api.FeedbackSubmissionPort;
import com.keevo.feedback.api.SubmitFeedbackInput;
import com.keevo.shared.application.port.sync.SyncOperation;
import com.keevo.shared.application.port.sync.SyncOperationResult;
import com.keevo.shared.application.port.sync.SyncOperationStatus;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * SubmitFeedbackSyncHandler — Offline sync handler for SUBMIT_FEEDBACK operations (Story 14.5, FR92).
 *
 * <p>Delegates to the published {@link FeedbackSubmissionPort} of the feedback module (Story 12.2e),
 * which checks the submitting tenant against the bound one.
 * The use case generates its own UUID — no pre-assigned device UUID needed for idempotency
 * since each submission is a new unique entry (no overwrite risk).
 */
@Component
public class SubmitFeedbackSyncHandler extends AbstractSyncOperationHandler {

    private final FeedbackSubmissionPort feedbackSubmission;

    public SubmitFeedbackSyncHandler(FeedbackSubmissionPort feedbackSubmission) {
        this.feedbackSubmission = feedbackSubmission;
    }

    @Override
    public Set<String> supportedTypes() {
        return Set.of("SUBMIT_FEEDBACK");
    }

    @Override
    public String moduleId() {
        return "core";
    }

    @Override
    protected void validate(SyncOperation operation) {
        Map<String, Object> p = operation.payload();
        Object typeObj = p.get("type");
        if (typeObj == null || !(typeObj instanceof String) || ((String) typeObj).isBlank())
            throw new IllegalArgumentException("Missing required field: type");
        Object descObj = p.get("description");
        if (descObj == null || !(descObj instanceof String) || ((String) descObj).isBlank())
            throw new IllegalArgumentException("Missing required field: description");
    }

    @Override
    protected SyncOperationResult apply(SyncOperation operation, UUID actorId, String tenantId) {
        Map<String, Object> p = operation.payload();

        var input = new SubmitFeedbackInput(
                String.valueOf(p.get("type")),
                String.valueOf(p.get("description")),
                p.get("appVersion") != null ? String.valueOf(p.get("appVersion")) : null,
                p.get("platform") != null ? String.valueOf(p.get("platform")) : null,
                p.get("screenContext") != null ? String.valueOf(p.get("screenContext")) : null,
                tenantId,
                actorId
        );

        UUID savedId = feedbackSubmission.submit(input);
        return new SyncOperationResult(operation.operationId(), SyncOperationStatus.APPLIED,
                savedId.toString(), null);
    }
}
