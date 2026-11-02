package com.keevo.identity.api;

import java.util.UUID;

/**
 * Immediate definitive deletion of a tenant and of its data (Story 12.2f), for the SUPER_ADMIN
 * force-delete, and the RGPD deletion trace of the super-admin actions (Story 16.6). Not
 * tenant-scoped: the tenant is designated by its id.
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

    /**
     * Story 16.6 — records a SUPER_ADMIN deletion request (force-delete, due now) in the durable,
     * non-personal deletion trace. Joins the caller's transaction when there is one.
     *
     * @throws NullPointerException if {@code tenantId} is null
     */
    void recordAdminDeletionRequest(UUID tenantId);

    /**
     * Story 16.6 — records a SUPER_ADMIN cancellation of a pending deletion in the durable,
     * non-personal deletion trace. Joins the caller's transaction when there is one.
     *
     * @throws NullPointerException if {@code tenantId} is null
     */
    void recordAdminDeletionCancel(UUID tenantId);
}
