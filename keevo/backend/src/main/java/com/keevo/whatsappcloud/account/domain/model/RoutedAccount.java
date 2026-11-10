package com.keevo.whatsappcloud.account.domain.model;

import java.util.UUID;

/** Story 21.5 — webhook routing: the live company (schema) owning a WABA number, by number id or WABA id. */
public record RoutedAccount(String routingKey, UUID tenantId, String tenantSchema) {
}
