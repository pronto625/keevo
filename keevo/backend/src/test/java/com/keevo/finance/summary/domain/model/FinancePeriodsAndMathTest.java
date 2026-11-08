package com.keevo.finance.summary.domain.model;

import com.keevo.finance.summary.domain.model.FinanceMath.Period;
import com.keevo.finance.summary.domain.model.FinancePeriods.InvalidPeriodException;
import com.keevo.finance.summary.domain.model.FinancePeriods.Resolved;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("20.11 — finance period resolution, comparison period and rounding")
class FinancePeriodsAndMathTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 8);

    private static Resolved resolve(String period, String start, String end) {
        return FinancePeriods.resolve(period, start, end, TODAY);
    }

    private static Period p(String start, String end) {
        return new Period(LocalDate.parse(start), LocalDate.parse(end));
    }

    @Test
    @DisplayName("defaults: the current month; each bound defaults on its own (Bref); compared with the window before")
    void defaults() {
        assertThat(resolve(null, null, null)).isEqualTo(new Resolved(p("2026-11-01", "2026-11-30"),
                p("2026-10-02", "2026-10-31")));
        assertThat(resolve(null, "2026-11-05", null).period()).isEqualTo(p("2026-11-05", "2026-11-30"));
        assertThat(resolve(" ", "", "2026-11-20").period()).isEqualTo(p("2026-11-01", "2026-11-20"));
    }

    @Test
    @DisplayName("period=yyyy-MM → that month vs the previous calendar month; yyyy → that year vs the previous year")
    void periods() {
        assertThat(resolve("2026-03", null, null)).isEqualTo(new Resolved(p("2026-03-01", "2026-03-31"),
                p("2026-02-01", "2026-02-28")));
        assertThat(resolve("2026-01", null, null).comparison()).isEqualTo(p("2025-12-01", "2025-12-31"));
        assertThat(resolve("2028-02", null, null).period()).isEqualTo(p("2028-02-01", "2028-02-29"));
        assertThat(resolve("2026", null, null)).isEqualTo(new Resolved(p("2026-01-01", "2026-12-31"),
                p("2025-01-01", "2025-12-31")));
        // the same month by dates: Bref's sliding window of equal length
        assertThat(resolve(null, "2026-03-01", "2026-03-31").comparison()).isEqualTo(p("2026-01-29", "2026-02-28"));
    }

    @Test
    @DisplayName("refusals carry the parameter and a French message")
    void refusals() {
        refused(() -> resolve("2026-13", null, null), "period", FinancePeriods.BAD_PERIOD);
        refused(() -> resolve("nov", null, null), "period", FinancePeriods.BAD_PERIOD);
        refused(() -> resolve("2026-11", "2026-11-01", null), "period", FinancePeriods.BOTH_FORMS);
        refused(() -> resolve(null, "2026-02-30", null), "startDate", FinancePeriods.BAD_DATE);
        refused(() -> resolve(null, "2026-11-01", "01/12/2026"), "endDate", FinancePeriods.BAD_DATE);
        refused(() -> resolve(null, "2026-11-10", "2026-11-09"), "startDate", FinancePeriods.INVERTED);
        refused(() -> resolve(null, null, "2026-10-31"), "endDate", FinancePeriods.INVERTED); // before the default start
        refused(() -> resolve(null, "2025-01-01", "2026-01-02"), "endDate", FinancePeriods.TOO_LONG);
        refused(() -> resolve("1999", null, null), "period", FinancePeriods.OUT_OF_RANGE);
        refused(() -> resolve("2101-01", null, null), "period", FinancePeriods.OUT_OF_RANGE);
        refused(() -> resolve(null, "1999-12-31", "2000-01-01"), "startDate", FinancePeriods.OUT_OF_RANGE);
    }

    private static void refused(Runnable call, String parameter, String message) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(InvalidPeriodException.class, e -> {
            assertThat(e.parameter()).isEqualTo(parameter);
            assertThat(e.getMessage()).isEqualTo(message);
        });
    }

    @Test
    @DisplayName("round2 = JavaScript Math.round(n × 100) / 100 on doubles; responses without trailing zeros")
    void round2() {
        assertThat(FinanceMath.round2(2.675)).isEqualTo(2.68);
        assertThat(FinanceMath.round2(1.005)).isEqualTo(1.0);
        assertThat(FinanceMath.round2(-2.345)).isEqualTo(-2.35); // -234.50000000000003 → -235, as Node
        assertThat(FinanceMath.dec(15000.0).toPlainString()).isEqualTo("15000");
        assertThat(FinanceMath.dec(12.5).toPlainString()).isEqualTo("12.5");
        assertThat(FinanceMath.dec(-0.0).toPlainString()).isEqualTo("0");
        assertThat(FinanceMath.pct(1, 3)).isEqualByComparingTo("33.33");
    }

    @Test
    @DisplayName("bucketKeys: a year by month gives 12 keys, a 45-day period stays daily")
    void buckets() {
        assertThat(FinanceMath.bucketKeys(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31),
                FinanceMath.Granularity.MONTH)).hasSize(12).startsWith("2026-01").endsWith("2026-12");
        assertThat(FinanceMath.pickGranularity(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 2, 14)))
                .isEqualTo(FinanceMath.Granularity.DAY);
        assertThat(FinanceMath.pickGranularity(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 2, 15)))
                .isEqualTo(FinanceMath.Granularity.MONTH);
        assertThat(List.of("2026-01-31", "2026-02-01")).isEqualTo(FinanceMath.bucketKeys(LocalDate.of(2026, 1, 31),
                LocalDate.of(2026, 2, 1), FinanceMath.Granularity.DAY));
    }
}
