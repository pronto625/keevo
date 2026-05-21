package com.keevo.admin.catalog.domain.port.in;

import com.keevo.admin.catalog.domain.model.AdminCatalogSummary;

public interface GetCatalogSummaryUseCase {
    AdminCatalogSummary execute();
}
