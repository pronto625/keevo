package com.keevo.feedback.feedback.domain.model;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** v1s-15-9: Feedback enforces the V7 NOT NULL columns. */
class FeedbackTest {

    private static Feedback build(String nullField) {
        return new Feedback(
                "id".equals(nullField) ? null : UUID.randomUUID(),
                "type".equals(nullField) ? null : "bug",
                "description".equals(nullField) ? null : "Ça plante",
                "tenantId".equals(nullField) ? null : "kv_abc123",
                "userId".equals(nullField) ? null : UUID.randomUUID(),
                null, null, null,
                "submittedAt".equals(nullField) ? null : Instant.now(),
                "priority".equals(nullField) ? null : "NORMAL");
    }

    @ParameterizedTest
    @ValueSource(strings = {"id", "type", "description", "tenantId", "userId", "submittedAt", "priority"})
    void requiredField_null_throws(String field) {
        assertThatThrownBy(() -> build(field))
                .isInstanceOf(NullPointerException.class)
                .hasMessage(field);
    }

    @Test
    void optionalFields_mayBeNull() {
        Feedback f = build("none");
        assertThat(f.getAppVersion()).isNull();
        assertThat(f.getPlatform()).isNull();
        assertThat(f.getScreenContext()).isNull();
    }
}
