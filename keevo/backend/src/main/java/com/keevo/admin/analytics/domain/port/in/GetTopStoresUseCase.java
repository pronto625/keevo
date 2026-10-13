package com.keevo.admin.analytics.domain.port.in;

import com.keevo.admin.analytics.domain.model.TopStoresReport;

public interface GetTopStoresUseCase {
    /** @param from inclusive ISO date, @param to inclusive ISO date — both null → last 30 days */
    TopStoresReport getTopStores(String from, String to);
}
