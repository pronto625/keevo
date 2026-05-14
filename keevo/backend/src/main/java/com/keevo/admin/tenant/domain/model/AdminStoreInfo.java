package com.keevo.admin.tenant.domain.model;

/**
 * AdminStoreInfo — lightweight store summary for the tenant detail drawer.
 */
public record AdminStoreInfo(
        String id,
        String name,
        String type,
        long totalRevenue
) {}
