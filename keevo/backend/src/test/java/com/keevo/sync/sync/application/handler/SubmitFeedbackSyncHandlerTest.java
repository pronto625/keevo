package com.keevo.sync.sync.application.handler;

import com.keevo.feedback.feedback.domain.model.Feedback;
import com.keevo.feedback.feedback.domain.port.in.SubmitFeedbackUseCase;
import com.keevo.sync.sync.domain.model.SyncOperation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * SubmitFeedbackSyncHandlerTest — Tests for {@link SubmitFeedbackSyncHandler} (Story 14.5).
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("SubmitFeedbackSyncHandler")
class SubmitFeedbackSyncHandlerTest {

    @Mock private SubmitFeedbackUseCase submitFeedbackUseCase;

    private SubmitFeedbackSyncHandler handler;

    @BeforeEach
    void setUp() {
        handler = new SubmitFeedbackSyncHandler(submitFeedbackUseCase);
    }

    @Test
    @DisplayName("supportedTypes returns SUBMIT_FEEDBACK")
    void supportedTypes_returnsSubmitFeedback() {
        assertThat(handler.supportedTypes()).isEqualTo(Set.of("SUBMIT_FEEDBACK"));
    }

    @Test
    @DisplayName("validate succeeds with valid payload")
    void validate_succeeds_withValidPayload() {
        SyncOperation operation = createOperation(Map.of(
                "type", "bug",
                "description", "Description du bug"
        ));

        // Should not throw
        handler.validate(operation);
    }

    @Test
    @DisplayName("validate fails when type is missing")
    void validate_fails_whenTypeMissing() {
        SyncOperation operation = createOperation(Map.of(
                "description", "Description du bug"
        ));

        assertThatThrownBy(() -> handler.validate(operation))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("type");
    }

    @Test
    @DisplayName("validate fails when description is missing")
    void validate_fails_whenDescriptionMissing() {
        SyncOperation operation = createOperation(Map.of(
                "type", "bug"
        ));

        assertThatThrownBy(() -> handler.validate(operation))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("description");
    }

    @Test
    @DisplayName("validate fails when type is not a String")
    void validate_fails_whenTypeNotString() {
        SyncOperation operation = createOperation(Map.of(
                "type", 123,
                "description", "Description du bug"
        ));

        assertThatThrownBy(() -> handler.validate(operation))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("type");
    }

    @Test
    @DisplayName("apply delegates to use case")
    void apply_delegatesToUseCase() {
        SyncOperation operation = createOperation(Map.of(
                "type", "bug",
                "description", "Description du bug",
                "appVersion", "1.0",
                "platform", "android",
                "screenContext", "/home"
        ));

        Feedback feedback = new Feedback(UUID.randomUUID(), "bug", "Description du bug",
                "kv_abc123", UUID.randomUUID(), "1.0", "android", "/home", Instant.now(), "NORMAL");
        when(submitFeedbackUseCase.submit(any())).thenReturn(feedback);

        var result = handler.apply(operation, UUID.randomUUID(), "kv_abc123");

        assertThat(result.status()).isEqualTo(com.keevo.sync.sync.domain.model.SyncOperationStatus.APPLIED);
    }

    private SyncOperation createOperation(Map<String, Object> payload) {
        return new SyncOperation(
                UUID.randomUUID(),
                "SUBMIT_FEEDBACK",
                payload,
                UUID.randomUUID().toString(),
                Instant.now()
        );
    }
}
