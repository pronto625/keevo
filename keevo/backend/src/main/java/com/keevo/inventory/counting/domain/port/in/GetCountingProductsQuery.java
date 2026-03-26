package com.keevo.inventory.counting.domain.port.in;

import java.util.UUID;

public record GetCountingProductsQuery(UUID sessionId) {}
