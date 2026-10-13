package com.keevo.admin.analytics.domain.model;

import java.util.List;

/** Feature adoption among the tenants active over the last 30 days (Story 9-4). */
public record AdoptionReport(long activeTenants, List<FeatureAdoption> features) {}
