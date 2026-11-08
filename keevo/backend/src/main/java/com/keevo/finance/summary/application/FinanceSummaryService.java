package com.keevo.finance.summary.application;

import com.keevo.expenses.api.ExpenseTotalsQueryPort;
import com.keevo.finance.summary.domain.model.FinanceFigures.CategoryAmount;
import com.keevo.finance.summary.domain.model.FinanceFigures.ExpenseBreakdown;
import com.keevo.finance.summary.domain.model.FinanceFigures.ExpenseTotals;
import com.keevo.finance.summary.domain.model.FinanceFigures.RevenueBreakdown;
import com.keevo.finance.summary.domain.model.FinanceMath;
import com.keevo.finance.summary.domain.model.FinanceMath.Granularity;
import com.keevo.finance.summary.domain.model.FinanceMath.Period;
import com.keevo.finance.summary.domain.model.FinancePeriods.Resolved;
import com.keevo.finance.summary.domain.model.FinanceSummaries;
import com.keevo.finance.summary.domain.model.FinanceSummary;
import com.keevo.finance.summary.domain.port.out.FinanceRevenuePort;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import com.keevo.store.api.StoreQueryPort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Story 20.11 — the finance summary of a period (FUS-FR26): a fixed number of set-based reads, then Bref's math
 * ({@link FinanceSummaries}). Reads: revenue of the period and of its comparison period (one grouped query), expenses
 * likewise (one grouped query), the store names of {@code byStore} (one query), the store check of an owner's filter
 * (one query). Never a loop of queries over days, stores or categories.
 */
@Service
public class FinanceSummaryService {

    /** Details key of the « unknown store » refusal ({@code VALIDATION_ERROR}). */
    public static final String STORE_FIELD = "storeId";
    static final String UNKNOWN_STORE = "Établissement inconnu";

    private final FinanceRevenuePort revenue;
    private final ExpenseTotalsQueryPort expenses;
    private final StoreQueryPort stores;

    public FinanceSummaryService(FinanceRevenuePort revenue, ExpenseTotalsQueryPort expenses, StoreQueryPort stores) {
        this.revenue = revenue;
        this.expenses = expenses;
        this.stores = stores;
    }

    /**
     * @param storeFilter an owner's {@code storeId=} (a store of the tenant, active or not — its past figures stay
     *                    readable — else {@code VALIDATION_ERROR} field {@code storeId}); ignored for a member, who is
     *                    pinned to their store
     */
    @Transactional(readOnly = true)
    public FinanceSummary summary(Resolved request, UUID storeFilter, FinanceAccess access, String tenantSchema) {
        Period period = request.period();
        Period comparison = request.comparison();
        UUID storeId;
        if (access.owner()) {
            if (storeFilter != null && stores.findById(tenantSchema, storeFilter).isEmpty()) {
                throw DomainException.userFacing(ErrorCode.VALIDATION_ERROR, UNKNOWN_STORE,
                        Map.of("field", STORE_FIELD));
            }
            storeId = storeFilter;
        } else if (access.assignedStoreId() == null) {
            return FinanceSummaries.assemble(period, comparison, RevenueBreakdown.EMPTY, ExpenseBreakdown.EMPTY,
                    false, Map.of());
        } else {
            storeId = access.assignedStoreId();
        }

        Granularity granularity = FinanceMath.pickGranularity(period.startDate(), period.endDate());
        ExpenseTotalsQueryPort.Bucket bucket = granularity == Granularity.MONTH
                ? ExpenseTotalsQueryPort.Bucket.MONTH : ExpenseTotalsQueryPort.Bucket.DAY;

        RevenueBreakdown revenueFigures = revenue.breakdown(tenantSchema, period, comparison, storeId, granularity);
        ExpenseBreakdown expenseFigures = expenses(expenses.breakdown(tenantSchema, comparison.startDate(),
                period.startDate(), period.endDate(), storeId, bucket));

        boolean withStores = storeId == null;
        Map<UUID, String> names = Map.of();
        if (withStores) {
            Set<UUID> ids = new HashSet<>(revenueFigures.excludingTaxByStore().keySet());
            ids.addAll(expenseFigures.byStore().keySet());
            names = ids.isEmpty() ? Map.of() : stores.namesByIds(tenantSchema, ids);
        }
        return FinanceSummaries.assemble(period, comparison, revenueFigures, expenseFigures, withStores, names);
    }

    private static ExpenseBreakdown expenses(ExpenseTotalsQueryPort.Breakdown b) {
        Map<String, BigDecimal> byBucket = b.byBucket().stream().collect(Collectors.toMap(
                ExpenseTotalsQueryPort.BucketTotal::key, ExpenseTotalsQueryPort.BucketTotal::amount,
                BigDecimal::add, HashMap::new));
        Map<UUID, BigDecimal> byStore = b.byStore().stream().collect(Collectors.toMap(
                ExpenseTotalsQueryPort.StoreTotal::storeId, ExpenseTotalsQueryPort.StoreTotal::amount,
                BigDecimal::add, HashMap::new));
        return new ExpenseBreakdown(new ExpenseTotals(b.totals().amount(), b.totals().count()),
                new ExpenseTotals(b.previous().amount(), b.previous().count()),
                b.byCategory().stream().map(c -> new CategoryAmount(c.category(), c.categoryLabel(), c.amount()))
                        .toList(), byBucket, byStore);
    }
}
