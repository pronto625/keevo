package com.keevo.store.store.domain.port.out;

/**
 * Checks and re-encodes an uploaded store logo (Story 19.12): PNG or JPEG recognised by its bytes, longest side
 * capped, metadata dropped. Refuses anything else with {@code STORE_LOGO_INVALID} (422).
 */
public interface StoreLogoNormalizer {

    StoreLogoStorage.StoredLogo normalize(byte[] upload);
}
