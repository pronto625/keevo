package com.keevo.assistant.guided.domain.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("21.4 — AssistantPeriod: Bref Point's periods and previous periods")
class AssistantPeriodTest {

    static final LocalDate TODAY = LocalDate.of(2026, 3, 10);

    @Test
    void currentAndPrevious() {
        assertThat(AssistantPeriod.TODAY.current(TODAY)).isEqualTo(new AssistantPeriod.Range(TODAY, TODAY));
        assertThat(AssistantPeriod.TODAY.previous(TODAY)).isEqualTo(new AssistantPeriod.Range(TODAY.minusDays(1),
                TODAY.minusDays(1)));
        assertThat(AssistantPeriod.WEEK.current(TODAY)).isEqualTo(new AssistantPeriod.Range(LocalDate.of(2026, 3, 4),
                TODAY));
        assertThat(AssistantPeriod.WEEK.previous(TODAY)).isEqualTo(new AssistantPeriod.Range(LocalDate.of(2026, 2, 25),
                LocalDate.of(2026, 3, 3)));
        assertThat(AssistantPeriod.MONTH.current(TODAY)).isEqualTo(new AssistantPeriod.Range(LocalDate.of(2026, 3, 1),
                TODAY));
        assertThat(AssistantPeriod.MONTH.previous(TODAY)).isEqualTo(new AssistantPeriod.Range(LocalDate.of(2026, 2, 1),
                LocalDate.of(2026, 2, 28)));
        assertThat(AssistantPeriod.YEAR.current(TODAY)).isEqualTo(new AssistantPeriod.Range(LocalDate.of(2026, 1, 1),
                TODAY));
        assertThat(AssistantPeriod.YEAR.previous(TODAY)).isEqualTo(new AssistantPeriod.Range(LocalDate.of(2025, 1, 1),
                LocalDate.of(2025, 12, 31)));
        assertThat(AssistantPeriod.MONTH.previous(LocalDate.of(2026, 1, 15)))
                .isEqualTo(new AssistantPeriod.Range(LocalDate.of(2025, 12, 1), LocalDate.of(2025, 12, 31)));
    }

    @Test
    void ids() {
        assertThat(AssistantPeriod.fromId(" Month ")).contains(AssistantPeriod.MONTH);
        assertThat(AssistantPeriod.fromId("mois")).isEmpty();
        assertThat(GuidedTopic.fromId("CALL_LIST")).contains(GuidedTopic.CALL_LIST);
        assertThat(GuidedTopic.fromId("stock")).isEmpty();
    }
}
