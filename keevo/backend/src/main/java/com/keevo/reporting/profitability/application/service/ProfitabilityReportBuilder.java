package com.keevo.reporting.profitability.application.service;

import com.keevo.reporting.profitability.domain.model.*;
import com.keevo.reporting.profitability.domain.model.ProductProfitabilityDetail.DailyMarginEntry;
import com.keevo.reporting.profitability.domain.port.in.GetProductProfitabilityUseCase.ProfitabilityQuery;
import com.keevo.reporting.profitability.domain.port.out.ProfitabilityRepository;
import com.keevo.reporting.profitability.domain.port.out.ProfitabilityRepository.*;
import com.keevo.shared.domain.exception.DomainException;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * ProfitabilityReportBuilder — GoF Builder pattern.
 *
 * <p>Assembles {@link ProductProfitabilityEntry} and
 * {@link ProductProfitabilityDetail} from raw repository rows.
 *
 * <p>Margin formula (MUST match PricingCalculator.java):
 * <pre>
 *   totalCost      = SUM((buyPrice + transportCost) * quantity)   [from raw row]
 *   totalRevenue   = SUM(applied_unit_price * quantity)            [from raw row]
 *   grossMarginXaf = totalRevenue - totalCost
 *   marginPercent  = (totalCost == 0) ? 0.0 : (grossMarginXaf / totalCost) * 100
 * </pre>
 *
 * Story 7.4, Task 7.1.
 */
@Component
public class ProfitabilityReportBuilder {

    private final ProfitabilityRepository repo;

    public ProfitabilityReportBuilder(ProfitabilityRepository repo) {
        this.repo = repo;
    }

    /**
     * Builds the flat profitability entry list for the given query.
     * Products with no sales in the period are excluded (handled by the SQL query).
     */
    public List<ProductProfitabilityEntry> buildEntries(ProfitabilityQuery query) {
        var rows = repo.findRawByPeriod(
                query.tenantId(), query.from(), query.to(), query.storeId());
        return rows.stream()
                .map(row -> toEntry(row, query.storeId()))
                .toList();
    }

    /**
     * Builds the full profitability detail for a single product.
     *
     * @throws DomainException PRODUCT_NOT_FOUND if no cost data found for the product.
     */
    public ProductProfitabilityDetail buildDetail(ProfitabilityQuery query, UUID productId) {
        // Fetch product cost info first — throws PRODUCT_NOT_FOUND only if product doesn't exist.
        var costs = repo.findProductCosts(productId)
                .orElseThrow(() -> new DomainException("PRODUCT_NOT_FOUND"));

        List<DailyMarginEntry> sparkline = repo.findDailyMarginLast7(
                query.tenantId(), productId, query.to()).stream()
                .map(r -> new DailyMarginEntry(r.date(), r.dailyMargin()))
                .toList();

        // Look for sales data in the requested period.
        var rows = repo.findRawByPeriod(
                query.tenantId(), query.from(), query.to(), query.storeId());
        var productRowOpt = rows.stream()
                .filter(r -> r.productId().equals(productId))
                .findFirst();

        if (productRowOpt.isPresent()) {
            // Sales found: compute full metrics.
            var entry = toEntry(productRowOpt.get(), query.storeId());
            return new ProductProfitabilityDetail(
                    entry.productId(), entry.productName(), entry.categoryName(),
                    entry.unitsSold(), entry.totalRevenue(), entry.totalCost(),
                    entry.grossMarginXaf(), entry.marginPercent(), entry.isLoss(), entry.storeId(),
                    costs.cataloguePrice(), costs.buyPrice(), costs.transportCost(),
                    costs.minAppliedPrice(), costs.maxAppliedPrice(), costs.avgAppliedPrice(),
                    sparkline,
                    null, null, 0
            );
        } else {
            // Product exists but no completed sales in this period: return zero metrics.
            return new ProductProfitabilityDetail(
                    productId, costs.productName(), costs.categoryName(),
                    0, 0L, 0L, 0L, 0.0, false, query.storeId(),
                    costs.cataloguePrice(), costs.buyPrice(), costs.transportCost(),
                    costs.minAppliedPrice(), costs.maxAppliedPrice(), costs.avgAppliedPrice(),
                    sparkline,
                    null, null, 0
            );
        }
    }

    // ── private helpers ──────────────────────────────────────────────────────

    private ProductProfitabilityEntry toEntry(RawProfitabilityRow row, UUID storeId) {
        long grossMarginXaf = row.totalRevenue() - row.totalCost();
        double marginPercent = row.totalCost() == 0
                ? 0.0
                : ((double) grossMarginXaf / row.totalCost()) * 100.0;
        boolean isLoss = grossMarginXaf < 0;
        return new ProductProfitabilityEntry(
                row.productId(), row.productName(), row.categoryName(),
                row.unitsSold(), row.totalRevenue(), row.totalCost(),
                grossMarginXaf, marginPercent, isLoss, storeId
        );
    }
}
