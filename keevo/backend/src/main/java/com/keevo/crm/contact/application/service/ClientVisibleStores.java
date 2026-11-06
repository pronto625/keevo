package com.keevo.crm.contact.application.service;

import java.util.Set;
import java.util.UUID;

/**
 * Story 20.2 — the stores whose clients a member of {@code storeId} sees, read through the store module and resolved
 * at most once per request (a REST call or a whole sync push batch).
 */
public interface ClientVisibleStores {

    Set<UUID> of(String tenantSchema, UUID storeId);
}
