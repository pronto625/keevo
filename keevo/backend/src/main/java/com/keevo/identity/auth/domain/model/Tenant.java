package com.keevo.identity.auth.domain.model;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Tenant — Domain model for a tenant (isolated workspace).
 *
 * <p>Pure Java — NO Spring, JPA, or framework imports.
 * Tenant code format: {@code KV-XXXXXX} (6 uppercase alphanumeric chars).
 * Schema name format: {@code kv_xxxxxx} (lowercase, derived from code).
 */
public final class Tenant {

    private final UUID id;
    private final String code;          // KV-ABC123
    private final String schemaName;    // kv_abc123
    private final String name;          // Human-readable name (e.g. "Boutique Simon")
    private final TenantStatus status;
    private final PlanType planType;
    private final Instant createdAt;
    private final Instant deletionScheduledAt;

    /** Legacy constructor (name defaults to code, deletionScheduledAt defaults to null). */
    public Tenant(UUID id, String code, String schemaName,
                  TenantStatus status, PlanType planType, Instant createdAt) {
        this(id, code, schemaName, code, status, planType, createdAt, null);
    }

    public Tenant(UUID id, String code, String schemaName, String name,
                  TenantStatus status, PlanType planType, Instant createdAt) {
        this(id, code, schemaName, name, status, planType, createdAt, null);
    }

    public Tenant(UUID id, String code, String schemaName, String name,
                  TenantStatus status, PlanType planType, Instant createdAt,
                  Instant deletionScheduledAt) {
        this.id         = Objects.requireNonNull(id,         "id must not be null");
        this.code       = Objects.requireNonNull(code,       "code must not be null");
        this.schemaName = Objects.requireNonNull(schemaName, "schemaName must not be null");
        this.name       = name != null ? name : code;
        this.status     = Objects.requireNonNull(status,     "status must not be null");
        this.planType   = Objects.requireNonNull(planType,   "planType must not be null");
        this.createdAt  = Objects.requireNonNull(createdAt,  "createdAt must not be null");
        this.deletionScheduledAt = deletionScheduledAt;
    }

    /** Derive schema name from tenant code. E.g., KV-ABC123 → kv_abc123 */
    public static String schemaNameFromCode(String code) {
        return "kv_" + code.replace("KV-", "").toLowerCase();
    }

    public UUID getId()             { return id; }
    public String getCode()         { return code; }
    public String getSchemaName()   { return schemaName; }
    /** Human-readable tenant name. Defaults to code when not explicitly set. */
    public String getName()         { return name; }
    public TenantStatus getStatus() { return status; }
    public PlanType getPlanType()   { return planType; }
    public Instant getCreatedAt()   { return createdAt; }
    public Instant getDeletionScheduledAt() { return deletionScheduledAt; }

    /** Returns new Tenant instance with updated status and deletionScheduledAt (Story 14.5). */
    public Tenant withStatus(TenantStatus newStatus, Instant newDeletionScheduledAt) {
        return new Tenant(id, code, schemaName, name, newStatus, planType, createdAt,
                          newDeletionScheduledAt);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Tenant t)) return false;
        return Objects.equals(id, t.id);
    }

    @Override
    public int hashCode() { return Objects.hash(id); }

    @Override
    public String toString() {
        return "Tenant{id=" + id + ", code='" + code + "', schema='" + schemaName + "'}";
    }
}
