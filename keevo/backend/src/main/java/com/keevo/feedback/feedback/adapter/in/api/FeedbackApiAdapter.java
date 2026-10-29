package com.keevo.feedback.feedback.adapter.in.api;

import com.keevo.feedback.api.FeedbackInboxPort;
import com.keevo.feedback.api.FeedbackListQuery;
import com.keevo.feedback.api.FeedbackSubmissionPort;
import com.keevo.feedback.api.FeedbackView;
import com.keevo.feedback.api.SubmitFeedbackInput;
import com.keevo.feedback.feedback.domain.model.Feedback;
import com.keevo.feedback.feedback.domain.port.in.HandleFeedbackUseCase;
import com.keevo.feedback.feedback.domain.port.in.ListFeedbackUseCase;
import com.keevo.feedback.feedback.domain.port.in.ListFeedbackUseCase.ListFeedbackQuery;
import com.keevo.feedback.feedback.domain.port.in.SubmitFeedbackUseCase;
import com.keevo.feedback.feedback.domain.port.in.SubmitFeedbackUseCase.SubmitFeedbackCommand;
import com.keevo.shared.infrastructure.persistence.TenantContext;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Implements the published feedback ports (Story 12.2e) over the existing use cases. No transaction
 * of its own. Feedback is platform-wide ({@code public} schema): only {@code submit} is guarded — when
 * a tenant context is bound, the input's {@code tenantSchema} must be that tenant.
 */
@Component
class FeedbackApiAdapter implements FeedbackSubmissionPort, FeedbackInboxPort {

    private final SubmitFeedbackUseCase submitFeedback;
    private final ListFeedbackUseCase listFeedback;
    private final HandleFeedbackUseCase handleFeedback;

    FeedbackApiAdapter(SubmitFeedbackUseCase submitFeedback,
                       ListFeedbackUseCase listFeedback,
                       HandleFeedbackUseCase handleFeedback) {
        this.submitFeedback = submitFeedback;
        this.listFeedback = listFeedback;
        this.handleFeedback = handleFeedback;
    }

    @Override
    public UUID submit(SubmitFeedbackInput input) {
        // A request/sync caller has its tenant bound: it may only file feedback for that tenant.
        // A platform-level caller (no bound tenant) is trusted to pass the submitting tenant.
        if (TenantContext.getCurrentTenant() != null) {
            TenantContext.requireCurrent(input.tenantSchema());
        }
        return submitFeedback.submit(new SubmitFeedbackCommand(
                input.type(), input.description(), input.appVersion(), input.platform(),
                input.screenContext(), input.tenantSchema(), input.userId())).getId();
    }

    @Override
    public Page<FeedbackView> list(FeedbackListQuery query) {
        return listFeedback.list(toInternal(query)).map(FeedbackApiAdapter::view);
    }

    @Override
    public long count(FeedbackListQuery query) {
        return listFeedback.count(toInternal(query));
    }

    @Override
    public FeedbackView markHandled(UUID feedbackId, UUID adminId) {
        return view(handleFeedback.markHandled(feedbackId, adminId));
    }

    @Override
    public FeedbackView markUnhandled(UUID feedbackId, UUID adminId) {
        return view(handleFeedback.markUnhandled(feedbackId, adminId));
    }

    private static ListFeedbackQuery toInternal(FeedbackListQuery q) {
        return new ListFeedbackQuery(q.page(), q.size(), q.priority(), q.screenContext(), q.type(), q.handled());
    }

    static FeedbackView view(Feedback f) {
        return new FeedbackView(f.getId(), f.getType(), f.getDescription(), f.getTenantId(), f.getUserId(),
                f.getAppVersion(), f.getPlatform(), f.getScreenContext(), f.getSubmittedAt(), f.getPriority(),
                f.getHandledAt(), f.getHandledBy());
    }
}
