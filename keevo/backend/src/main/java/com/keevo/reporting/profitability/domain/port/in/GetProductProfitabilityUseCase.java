package com.keevo.reporting.profitability.domain.port.in;

import com.keevo.reporting.profitability.domain.model.ProductProfitabilityDetail;
import com.keevo.reporting.profitability.domain.model.ProductProfitabilityEntry;
import com.keevo.reporting.profitability.domain.model.SortOption;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * GetProductProfitabilityUseCase — inbound port for product profitability queries.
 * Story 7.4, Task 6.1.
 */
public interface GetProductProfitabilityUseCase {

    record ProfitabilityQuery(
            String tenantId,
            LocalDate from,
            LocalDate to,
            SortOption sort,
            UUID storeId   // nullable — null = all stores
    ) {}

    /**
     * Returns a list of profitability entries for products that had at least
     * one completed sale in the given date range.
     */
    List<ProductProfitabilityEntry> getEntries(ProfitabilityQuery query);

    /**
     * Returns the full profitability detail for a single product.
     *
     * @throws com.keevo.shared.domain.exception.DomainException PRODUCT_NOT_FOUND
     *         if the product has no sales in the given period or does not exist.
     */
    ProductProfitabilityDetail getDetail(ProfitabilityQuery query, UUID productId);
}
