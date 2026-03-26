package com.keevo.inventory.counting.domain.port.in;

import java.util.UUID;

public record GetSessionCountsQuery(UUID sessionId) {}
