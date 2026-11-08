package com.keevo.finance.summary.application;

import com.keevo.expenses.api.ExpenseTotalsQueryPort;
import com.keevo.finance.summary.domain.model.FinanceFigures.RevenueBreakdown;
import com.keevo.finance.summary.domain.model.FinanceFigures.RevenueTotals;
import com.keevo.finance.summary.domain.model.FinanceMath.Granularity;
import com.keevo.finance.summary.domain.model.FinanceMath.Period;
import com.keevo.finance.summary.domain.model.FinancePeriods.Resolved;
import com.keevo.finance.summary.domain.model.FinanceSummaries;
import com.keevo.finance.summary.domain.model.FinanceSummary;
import com.keevo.finance.summary.domain.port.out.FinanceRevenuePort;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.store.api.StoreQueryPort;
import com.keevo.store.api.StoreView;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@DisplayName("20.11 — finance summary use case: store scope, fixed reads, byStore")
class FinanceSummaryServiceTest {

    private static final String TENANT = "kv_fin011";
    private static final Period SEPTEMBER = new Period(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30));
    private static final Period AUGUST = new Period(LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 31));
    private static final Resolved REQUEST = new Resolved(SEPTEMBER, AUGUST);
    private static final UUID STORE_A = UUID.randomUUID();
    private static final UUID STORE_B = UUID.randomUUID();

    private final FinanceRevenuePort revenue = mock(FinanceRevenuePort.class);
    private final ExpenseTotalsQueryPort expenses = mock(ExpenseTotalsQueryPort.class);
    private final StoreQueryPort stores = mock(StoreQueryPort.class);
    private final FinanceSummaryService service = new FinanceSummaryService(revenue, expenses, stores);

    private void figures(UUID storeId) {
        when(revenue.breakdown(TENANT, SEPTEMBER, AUGUST, storeId, Granularity.DAY))
                .thenReturn(new RevenueBreakdown(new RevenueTotals(new BigDecimal("16925"), new BigDecimal("1925"),
                        BigDecimal.ZERO, 2), RevenueTotals.ZERO, Map.of("2026-09-03", new BigDecimal("15000")),
                        Map.of(STORE_A, new BigDecimal("10000")), new BigDecimal("5000")));
        when(expenses.breakdown(TENANT, AUGUST.startDate(), SEPTEMBER.startDate(), SEPTEMBER.endDate(), storeId,
                ExpenseTotalsQueryPort.Bucket.DAY)).thenReturn(new ExpenseTotalsQueryPort.Breakdown(
                new ExpenseTotalsQueryPort.Totals(new BigDecimal("4000.50"), 2), ExpenseTotalsQueryPort.Totals.ZERO,
                List.of(new ExpenseTotalsQueryPort.CategoryTotal("rent", "Loyer", new BigDecimal("4000.50"))),
                List.of(new ExpenseTotalsQueryPort.BucketTotal("2026-09-05", new BigDecimal("4000.50"))),
                List.of(new ExpenseTotalsQueryPort.StoreTotal(STORE_B, new BigDecimal("4000.50")))));
    }

    @Test
    @DisplayName("owner, every store: Bref figures; byStore from both sides + « Sans boutique », sorted by result; "
            + "the rows add up to the revenue; names in one lookup")
    void ownerAllStores() {
        figures(null);
        when(stores.namesByIds(eq(TENANT), any())).thenReturn(Map.of(STORE_A, "Akwa", STORE_B, "Bonapriso"));
        FinanceSummary s = service.summary(REQUEST, null, FinanceAccess.OWNER, TENANT);

        assertThat(s.revenue().excludingTax()).isEqualByComparingTo("15000");
        assertThat(s.result().net()).isEqualByComparingTo("10999.5");
        assertThat(s.comparison().previousPeriod().startDate()).isEqualTo(AUGUST.startDate());
        assertThat(s.series()).hasSize(30);
        assertThat(s.byStore()).extracting(FinanceSummary.StoreResult::storeName)
                .containsExactly("Akwa", FinanceSummaries.WITHOUT_STORE, "Bonapriso");
        assertThat(s.byStore().get(1).storeId()).isNull();
        assertThat(s.byStore().stream().map(FinanceSummary.StoreResult::revenue).reduce(BigDecimal.ZERO,
                BigDecimal::add)).isEqualByComparingTo(s.revenue().excludingTax());
        verify(stores).namesByIds(TENANT, Set.of(STORE_A, STORE_B));
    }

    @Test
    @DisplayName("owner with storeId: the store is checked, figures of that store, byStore empty")
    void ownerOneStore() {
        figures(STORE_A);
        when(stores.findById(TENANT, STORE_A)).thenReturn(Optional.of(mock(StoreView.class)));
        FinanceSummary s = service.summary(REQUEST, STORE_A, FinanceAccess.OWNER, TENANT);
        assertThat(s.byStore()).isEmpty();
        verify(stores, never()).namesByIds(any(), any());
    }

    @Test
    @DisplayName("owner with an unknown store → VALIDATION_ERROR « Établissement inconnu », nothing read")
    void unknownStore() {
        when(stores.findById(TENANT, STORE_A)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.summary(REQUEST, STORE_A, FinanceAccess.OWNER, TENANT))
                .isInstanceOfSatisfying(DomainException.class, e -> {
                    assertThat(e.getDetails()).containsEntry("field", "storeId");
                    assertThat(e.getMessage()).contains("Établissement inconnu");
                });
        verifyNoInteractions(revenue, expenses);
    }

    @Test
    @DisplayName("member: pinned to their store whatever the filter; without store → zeros, nothing read")
    void member() {
        figures(STORE_B);
        FinanceSummary s = service.summary(REQUEST, STORE_A, FinanceAccess.member(STORE_B), TENANT);
        assertThat(s.byStore()).isEmpty();
        verify(revenue).breakdown(TENANT, SEPTEMBER, AUGUST, STORE_B, Granularity.DAY);
        verify(stores, never()).findById(any(), any());

        FinanceSummary none = service.summary(REQUEST, null, FinanceAccess.member(null), TENANT);
        assertThat(none.revenue().ttc()).isEqualByComparingTo("0");
        assertThat(none.series()).hasSize(30);
    }
}
