package com.keevo.migration.domain.model;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * A Bref {@code user} row (owner or member) as read from the source, unchanged (Story 24.2). Field names follow the Bref
 * entity ({@code api-bref/src/users/.../user.entity.ts}).
 *
 * @param role         {@code owner}, {@code admin}, {@code seller}, {@code viewer} (or {@code superadmin})
 * @param verified     Bref {@code isVerified}: the sign-up code was confirmed
 * @param passwordHash bcrypt hash ({@code $2b$…}), {@code null} for an account signing in by code or social login
 * @param shopId       a member's shop ({@code null} for an owner)
 */
public record BrefAccount(UUID id, String role, String email, boolean emailVerified, String phone, boolean verified,
                          String username, String passwordHash, String firstName, String lastName, String shopId,
                          boolean active, boolean mustChangePassword, String provider, String socialId,
                          Instant createdAt, Instant updatedAt, Instant deletedAt) {

    public BrefAccount {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
    }

    public boolean deleted() {
        return deletedAt != null;
    }

    /** Canonical form of the source row (every field, fixed order) for the record checksum. */
    public String canonical() {
        return Canonical.of("id", id, "role", role, "email", email, "emailVerified", emailVerified, "phone", phone,
                "isVerified", verified, "username", username, "password", passwordHash, "firstName", firstName,
                "lastName", lastName, "shopId", shopId, "isActive", active, "mustChangePassword", mustChangePassword,
                "provider", provider, "socialId", socialId, "createdAt", createdAt, "updatedAt", updatedAt,
                "deletedAt", deletedAt);
    }

    /** No personal data nor secret in logs and test output. */
    @Override
    public String toString() {
        return "BrefAccount[" + id + ", " + role + "]";
    }
}
