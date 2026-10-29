package com.keevo.identity.api;

import java.util.UUID;

/**
 * Immediate definitive deletion of a tenant and of its data (Story 12.2f), for the SUPER_ADMIN
 * force-delete. Not tenant-scoped: the tenant is designated by its id.
 */
public interface TenantDeletionPort {

    /**
     * Deletes the tenant if it is {@code DELETION_PENDING}; the caller marks it so beforehand.
     *
     * @return {@code true} when the tenant was deleted; {@code false} when it was not
     *         {@code DELETION_PENDING} (e.g. cancelled) or was deleted concurrently. Failures
     *         propagate as exceptions.
     * @throws NullPointerException if {@code tenantId} is null
     */
    boolean deleteNow(UUID tenantId);
}
