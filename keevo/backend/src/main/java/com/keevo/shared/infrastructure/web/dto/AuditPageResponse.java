package com.keevo.shared.infrastructure.web.dto;

import java.util.List;

/**
 * AuditPageResponse — paginated REST response for audit log queries.
 *
 * <p>Story 1.8 / Pagination — returned by {@link com.keevo.shared.infrastructure.web.AuditController}.
 *
 * <p>Fields:
 * <ul>
 *   <li>{@code entries} — audit entries for the requested page, sorted occurredAt DESC</li>
 *   <li>{@code hasMore} — true if at least one more page exists</li>
 *   <li>{@code page}    — zero-based page index echoed back to the client</li>
 *   <li>{@code size}    — page size echoed back to the client</li>
 * </ul>
 */
public record AuditPageResponse(
        List<AuditEntryResponse> entries,
        boolean hasMore,
        int page,
        int size
) {}
