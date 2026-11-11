package com.keevo.migration.domain.model;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * A Bref {@code shop} row as read from the source (Story 24.2). Field names follow
 * {@code api-bref/src/shop/.../shop.entity.ts}.
 *
 * @param photoUrl  the logo's URL (Cloudinary in production), nullable
 * @param photo     the logo's bytes when the export embeds them ({@code photoData}), else {@code null}; not part of
 *                  the row checksum (the database adapter never has it)
 */
public record BrefShop(UUID id, UUID ownerId, String name, String address, String category, String description,
                       String phone, String photoUrl, String niu, String rccm, String country, boolean active,
                       Instant createdAt, Instant updatedAt, Instant deletedAt, byte[] photo) {

    public BrefShop {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
    }

    public boolean deleted() {
        return deletedAt != null;
    }

    public String canonical() {
        return Canonical.of("id", id, "userId", ownerId, "name", name, "address", address, "category", category,
                "description", description, "phone", phone, "photoUrl", photoUrl, "niu", niu, "rccm", rccm,
                "country", country, "isActive", active, "createdAt", createdAt, "updatedAt", updatedAt,
                "deletedAt", deletedAt);
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof BrefShop other && canonical().equals(other.canonical());
    }

    @Override
    public int hashCode() {
        return canonical().hashCode();
    }

    @Override
    public String toString() {
        return "BrefShop[" + id + "]";
    }
}
