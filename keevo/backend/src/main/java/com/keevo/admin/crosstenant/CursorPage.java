package com.keevo.admin.crosstenant;

import java.util.List;

/**
 * Story 20.13 — one keyset page of a cross-account list: the items, the opaque cursor of the next page (null on the
 * last page) and whether more items follow.
 */
public record CursorPage<T>(List<T> items, String nextCursor, boolean hasMore) {

    public CursorPage {
        items = List.copyOf(items);
    }
}
