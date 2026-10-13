package com.keevo.feedback.feedback.adapter.out.persistence.impl;

import com.keevo.feedback.feedback.domain.model.Feedback;
import com.keevo.feedback.feedback.domain.port.out.FeedbackRepository.FeedbackCriteria;
import com.keevo.shared.infrastructure.persistence.PostgresIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Spec 9-1 completion — V19 handled columns + ANDed inbox filters on a real public.feedback. */
@DataJpaTest(properties = "spring.jpa.hibernate.ddl-auto=validate")
@Import(FeedbackRepositoryAdapter.class)
@DisplayName("Feedback inbox persistence on PostgreSQL (spec 9-1)")
class FeedbackRepositoryAdapterPostgresIntegrationTest extends PostgresIntegrationTest {

    @Autowired FeedbackRepositoryAdapter adapter;

    @Test
    void handledFlagAndFilters() {
        String screen = "/it-" + UUID.randomUUID().toString().substring(0, 8);
        Feedback bugHigh = adapter.save(feedback("BUG", "HIGH", screen, "2026-10-12T09:00:00Z"));
        Feedback suggestion = adapter.save(feedback("SUGGESTION", "NORMAL", screen, "2026-10-12T10:00:00Z"));
        PageRequest page = PageRequest.of(0, 20, Sort.by(Sort.Direction.DESC, "submittedAt"));

        assertThat(adapter.search(new FeedbackCriteria(null, screen, null, false), page).getContent())
                .extracting(Feedback::getId).containsExactly(suggestion.getId(), bugHigh.getId());
        assertThat(adapter.search(new FeedbackCriteria("HIGH", screen, "bug", null), page).getContent())
                .extracting(Feedback::getId).containsExactly(bugHigh.getId());

        assertThat(adapter.count(new FeedbackCriteria("HIGH", screen, null, false))).isEqualTo(1L);

        UUID admin = UUID.randomUUID();
        Feedback handled = adapter.setHandled(bugHigh.getId(), Instant.parse("2026-10-13T08:00:00Z"), admin).orElseThrow();
        assertThat(handled.isHandled()).isTrue();
        assertThat(handled.getHandledBy()).isEqualTo(admin);
        assertThat(adapter.search(new FeedbackCriteria(null, screen, null, false), page).getContent())
                .extracting(Feedback::getId).containsExactly(suggestion.getId());
        assertThat(adapter.search(new FeedbackCriteria(null, screen, null, true), page).getContent())
                .extracting(Feedback::getId).containsExactly(bugHigh.getId());
        assertThat(adapter.count(new FeedbackCriteria("HIGH", screen, null, false))).isZero();

        Feedback back = adapter.setHandled(bugHigh.getId(), null, admin).orElseThrow();
        assertThat(back.isHandled()).isFalse();
        assertThat(back.getHandledBy()).isNull();
        assertThat(adapter.setHandled(UUID.randomUUID(), Instant.now(), admin)).isEmpty();
    }

    private static Feedback feedback(String type, String priority, String screen, String at) {
        return new Feedback(UUID.randomUUID(), type, "Description du retour", "kv_abc123", UUID.randomUUID(),
                "1.0", "android", screen, Instant.parse(at), priority);
    }
}
