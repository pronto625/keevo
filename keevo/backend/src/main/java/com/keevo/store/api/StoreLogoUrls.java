package com.keevo.store.api;

import java.util.UUID;

/**
 * The API URL of a store logo (Story 19.12) — the single place it is built (REST responses, sync delta).
 * {@code /api/v1/stores/{id}/logo?v=<token>}: the token comes from the stored file name, which is new on every
 * upload, so the URL changes exactly when the logo does (clients may cache it) and never on other edits.
 */
public final class StoreLogoUrls {

    private StoreLogoUrls() {
    }

    /** {@code null} when the store has no logo ({@code logoPath} null). */
    public static String of(UUID storeId, String logoPath) {
        if (storeId == null || logoPath == null) {
            return null;
        }
        int dot = logoPath.lastIndexOf('.');
        String stem = dot > 0 ? logoPath.substring(0, dot) : logoPath;
        String token = stem.substring(stem.lastIndexOf('-') + 1);
        return "/api/v1/stores/" + storeId + "/logo?v=" + token;
    }
}
