package com.keevo.admin.analytics.domain.model;

import java.time.LocalDate;
import java.util.List;

/** Top stores by GMV over the inclusive WAT day range {@code from..to} (Story 9-4). */
public record TopStoresReport(LocalDate from, LocalDate to, List<TopStore> stores) {}
