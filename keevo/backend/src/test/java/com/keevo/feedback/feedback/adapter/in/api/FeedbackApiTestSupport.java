package com.keevo.feedback.feedback.adapter.in.api;

import com.keevo.feedback.api.FeedbackInboxPort;
import com.keevo.feedback.api.FeedbackSubmissionPort;
import com.keevo.feedback.api.FeedbackView;
import com.keevo.feedback.feedback.domain.model.Feedback;
import com.keevo.feedback.feedback.domain.port.in.HandleFeedbackUseCase;
import com.keevo.feedback.feedback.domain.port.in.ListFeedbackUseCase;
import com.keevo.feedback.feedback.domain.port.in.SubmitFeedbackUseCase;

/**
 * Test-only access to the package-private {@code feedback :: api} adapter (Story 12.2e), for tests of
 * other packages that drive the real adapter over mocked use cases.
 */
public final class FeedbackApiTestSupport {

    private FeedbackApiTestSupport() {}

    public static FeedbackSubmissionPort submissionPort(SubmitFeedbackUseCase submit) {
        return new FeedbackApiAdapter(submit, null, null);
    }

    public static FeedbackInboxPort inboxPort(ListFeedbackUseCase list, HandleFeedbackUseCase handle) {
        return new FeedbackApiAdapter(null, list, handle);
    }

    /** The published view of a feedback, mapped exactly as the adapter does. */
    public static FeedbackView view(Feedback feedback) {
        return FeedbackApiAdapter.view(feedback);
    }
}
