package com.keevo.admin.analytics.domain.model;

/**
 * One store of the GMV ranking (Story 9-4). Amounts in XAF.
 *
 * @param averageBasket gmv / transactions (rounded), 0 without transaction
 * @param growthPercent GMV variation vs the previous period of equal length (%, 1 decimal), null when
 *                      the previous GMV is 0 (« Nouveau »)
 */
public record TopStore(String storeName, String tenantName, long gmv, long transactions, long averageBasket,
                       Double growthPercent) {}
