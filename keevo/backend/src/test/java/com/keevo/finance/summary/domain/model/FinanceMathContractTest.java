package com.keevo.finance.summary.domain.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.keevo.finance.FinanceContract;
import com.keevo.finance.summary.domain.model.FinanceFigures.CategoryAmount;
import com.keevo.finance.summary.domain.model.FinanceFigures.ExpenseBreakdown;
import com.keevo.finance.summary.domain.model.FinanceFigures.ExpenseTotals;
import com.keevo.finance.summary.domain.model.FinanceFigures.RevenueBreakdown;
import com.keevo.finance.summary.domain.model.FinanceFigures.RevenueTotals;
import com.keevo.finance.summary.domain.model.FinanceMath.Granularity;
import com.keevo.finance.summary.domain.model.FinanceMath.Period;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.keevo.finance.FinanceContract.assertSubset;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 20.11 (AC) — every case of {@code keevo/contracts/finance-math-cases.json}: the cases of Bref's
 * {@code finance-math.spec.ts}, function by function, JavaScript's rounding on half cents, and the summary assembly of
 * {@code FinanceService.getSummary} on raw totals. The expected objects are matched as subsets (a case states only what
 * Bref's spec asserts), numbers by value. The summary cases are also run end to end by {@code FinanceFullContextTest}.
 */
@DisplayName("20.11 — contract finance-math-cases.json (Bref finance-math.spec.ts + getSummary)")
class FinanceMathContractTest {

    private static final ObjectMapper JSON = new ObjectMapper().registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    private static JsonNode contract;

    @BeforeAll
    static void load() throws Exception {
        contract = FinanceContract.load(JSON);
    }

    private static LocalDate date(JsonNode n, String field) {
        return LocalDate.parse(n.path(field).asText());
    }

    private static BigDecimal num(JsonNode n) {
        return n.decimalValue();
    }

    @Test
    @DisplayName("daysInclusive, previousPeriod, pickGranularity, bucketKeys — every Bref case")
    void periods() {
        assertThat(contract.path("daysInclusive")).hasSize(3);
        for (JsonNode c : contract.path("daysInclusive")) {
            assertThat(FinanceMath.daysInclusive(date(c, "startDate"), date(c, "endDate"))).as(c.toString())
                    .isEqualTo(c.path("expected").asLong());
        }
        assertThat(contract.path("previousPeriod")).hasSize(3);
        for (JsonNode c : contract.path("previousPeriod")) {
            Period p = FinanceMath.previousPeriod(date(c, "startDate"), date(c, "endDate"));
            assertThat(p).as(c.toString()).isEqualTo(new Period(date(c.path("expected"), "startDate"),
                    date(c.path("expected"), "endDate")));
        }
        assertThat(contract.path("pickGranularity")).hasSize(2);
        for (JsonNode c : contract.path("pickGranularity")) {
            assertThat(FinanceMath.pickGranularity(date(c, "startDate"), date(c, "endDate")).key()).as(c.toString())
                    .isEqualTo(c.path("expected").asText());
        }
        assertThat(contract.path("bucketKeys")).hasSize(3);
        for (JsonNode c : contract.path("bucketKeys")) {
            Granularity g = "month".equals(c.path("granularity").asText()) ? Granularity.MONTH : Granularity.DAY;
            List<String> expected = new ArrayList<>();
            c.path("expected").forEach(k -> expected.add(k.asText()));
            assertThat(FinanceMath.bucketKeys(date(c, "startDate"), date(c, "endDate"), g)).as(c.toString())
                    .containsExactlyElementsOf(expected);
        }
    }

    @Test
    @DisplayName("computeDelta, pct and round2 — every Bref case, division by zero → null, half cents as JavaScript")
    void deltasAndPercentages() {
        assertThat(contract.path("computeDelta")).hasSize(4);
        for (JsonNode c : contract.path("computeDelta")) {
            JsonNode actual = JSON.valueToTree(FinanceMath.computeDelta(c.path("current").asDouble(),
                    c.path("previous").asDouble()));
            assertSubset(c.path("expected"), actual, c.toString());
        }
        assertThat(contract.path("pct")).hasSize(2);
        for (JsonNode c : contract.path("pct")) {
            BigDecimal actual = FinanceMath.pct(c.path("part").asDouble(), c.path("whole").asDouble());
            if (c.path("expected").isNull()) {
                assertThat(actual).as(c.toString()).isNull();
            } else {
                assertThat(actual).as(c.toString()).isEqualByComparingTo(num(c.path("expected")));
            }
        }
        assertThat(contract.path("round2")).hasSize(5);
        for (JsonNode c : contract.path("round2")) {
            assertThat(FinanceMath.dec(FinanceMath.round2(c.path("value").asDouble()))).as(c.toString())
                    .isEqualByComparingTo(num(c.path("expected")));
        }
    }

    @Test
    @DisplayName("summary assembly — Bref's getSummary formulas on raw totals")
    void summaries() {
        assertThat(contract.path("summary")).hasSizeGreaterThanOrEqualTo(2);
        for (JsonNode c : contract.path("summary")) {
            Period period = new Period(date(c.path("period"), "startDate"), date(c.path("period"), "endDate"));
            JsonNode r = c.path("revenue");
            Map<String, BigDecimal> revenueByBucket = new HashMap<>();
            r.path("excludingTaxByBucket").fields().forEachRemaining(e -> revenueByBucket.put(e.getKey(),
                    num(e.getValue())));
            JsonNode e = c.path("expenses");
            Map<String, BigDecimal> expensesByBucket = new HashMap<>();
            e.path("byBucket").fields().forEachRemaining(x -> expensesByBucket.put(x.getKey(), num(x.getValue())));
            List<CategoryAmount> categories = new ArrayList<>();
            e.path("byCategory").forEach(x -> categories.add(new CategoryAmount(x.path("category").asText(),
                    x.path("categoryLabel").asText(), num(x.path("amount")))));

            FinanceSummary summary = FinanceSummaries.assemble(period,
                    FinanceMath.previousPeriod(period.startDate(), period.endDate()),
                    new RevenueBreakdown(totals(r), totals(c.path("previousRevenue")), revenueByBucket, Map.of(),
                            BigDecimal.ZERO),
                    new ExpenseBreakdown(new ExpenseTotals(num(e.path("total")), e.path("count").asLong()),
                            new ExpenseTotals(num(c.path("previousExpenses").path("total")),
                                    c.path("previousExpenses").path("count").asLong()),
                            categories, expensesByBucket, Map.of()),
                    false, Map.of());
            JsonNode actual = JSON.valueToTree(summary);
            FinanceContract.assertSummary(c.path("expected"), actual, c.path("name").asText());
            assertThat(actual.path("byStore")).isEmpty();
        }
    }

    private static RevenueTotals totals(JsonNode n) {
        return new RevenueTotals(num(n.path("ttc")), num(n.path("tax")), num(n.path("paid")), n.path("count").asLong());
    }
}
