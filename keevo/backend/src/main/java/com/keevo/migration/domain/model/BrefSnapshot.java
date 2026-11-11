package com.keevo.migration.domain.model;

import java.util.List;
import java.util.Objects;

/**
 * Story 24.2 — what the import reads of one Bref owner: the owner, every shop of the owner (deleted ones included, so
 * the documents of 24.4 keep their store), every member account attached to those shops (deleted ones included, the
 * import skips and counts them). Both source adapters produce the same snapshot for the same data. 24.3–24.6 extend it
 * with their sections.
 */
public record BrefSnapshot(BrefAccount owner, List<BrefShop> shops, List<BrefAccount> members, SourceKind source) {

    public BrefSnapshot {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(source, "source");
        shops = List.copyOf(shops);
        members = List.copyOf(members);
    }
}
