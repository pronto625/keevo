package com.keevo.store.store.domain.port.in;

import com.keevo.store.store.domain.model.Store;
import com.keevo.store.store.domain.port.out.StoreLogoStorage.StoredLogo;

import java.util.UUID;

/** The logo of a store (Story 19.12): set / replace, remove (owner), read (every role). Current tenant. */
public interface StoreLogoUseCase {

    /**
     * Checks the size ({@code STORE_LOGO_TOO_LARGE}), re-encodes ({@code STORE_LOGO_INVALID}), stores the file, points
     * the store at it and bumps {@code updated_at} (the sync delta carries the change). Audited.
     *
     * @throws com.keevo.shared.domain.exception.DomainException STORE_NOT_FOUND
     */
    Store upload(UUID storeId, byte[] upload, UUID actorId);

    /** Removes the logo (no-op, no audit, when there is none). @throws DomainException STORE_NOT_FOUND */
    Store delete(UUID storeId, UUID actorId);

    /** @throws com.keevo.shared.domain.exception.DomainException STORE_NOT_FOUND, STORE_LOGO_NOT_FOUND */
    StoredLogo read(UUID storeId);
}
