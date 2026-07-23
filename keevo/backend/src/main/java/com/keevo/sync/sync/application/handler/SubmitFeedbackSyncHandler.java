package com.keevo.sync.sync.application.handler;

import com.keevo.feedback.feedback.domain.model.Feedback;
import com.keevo.feedback.feedback.domain.port.in.SubmitFeedbackUseCase;
import com.keevo.sync.sync.domain.model.SyncOperation;
import com.keevo.sync.sync.domain.model.SyncOperationResult;
import com.keevo.sync.sync.domain.model.SyncOperationStatus;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * SubmitFeedbackSyncHandler — Offline sync handler for SUBMIT_FEEDBACK operations (Story 14.5, FR92).
 *
 * <p>Delegates to {@link SubmitFeedbackUseCase} (same service as the REST endpoint).
 * The use case generates its own UUID — no pre-assigned device UUID needed for idempotency
 * since each submission is a new unique entry (no overwrite risk).
 */
@Component
public class SubmitFeedbackSyncHandler extends AbstractSyncOperationHandler {

    private final SubmitFeedbackUseCase submitFeedbackUseCase;

    public SubmitFeedbackSyncHandler(SubmitFeedbackUseCase submitFeedbackUseCase) {
        this.submitFeedbackUseCase = submitFeedbackUseCase;
    }

    @Override
    public Set<String> supportedTypes() {
        return Set.of("SUBMIT_FEEDBACK");
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

        var command = new SubmitFeedbackUseCase.SubmitFeedbackCommand(
                String.valueOf(p.get("type")),
                String.valueOf(p.get("description")),
                p.get("appVersion") != null ? String.valueOf(p.get("appVersion")) : null,
                p.get("platform") != null ? String.valueOf(p.get("platform")) : null,
                p.get("screenContext") != null ? String.valueOf(p.get("screenContext")) : null,
                tenantId,
                actorId
        );

        Feedback saved = submitFeedbackUseCase.submit(command);
        return new SyncOperationResult(operation.operationId(), SyncOperationStatus.APPLIED,
                saved.getId().toString(), null);
    }
}
