package com.keevo.admin.subscription.domain.port.in;

import java.util.UUID;

/** DowngradeToFreeCommand — move a tenant back to FREE (Story 9-2). {@code reason} optional. */
public record DowngradeToFreeCommand(UUID actorId, UUID tenantId, String reason) {}
