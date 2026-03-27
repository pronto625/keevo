package com.keevo.inventory.counting.domain.port.in;

import java.util.UUID;

/**
 * Query to generate a gap analysis report for an inventory session.
 * Story 6.3.
 */
public record GenerateGapReportQuery(UUID sessionId, UUID actorId) {}
