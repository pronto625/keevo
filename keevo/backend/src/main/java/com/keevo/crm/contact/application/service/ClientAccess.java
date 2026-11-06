package com.keevo.crm.contact.application.service;

import java.util.UUID;

/**
 * Story 20.2 — who acts on clients, as read by the inbound adapters (REST controller, sync handlers) from the
 * authenticated actor; the use cases never read the security context.
 *
 * @param owner           OWNER — unrestricted (may also pick or change a client's store)
 * @param assignedStoreId the store member's store (EMPLOYEE, MANAGER, VIEWER); {@code null} for an OWNER or a member
 *                        without store (who then sees the legacy clients only and cannot create)
 */
public record ClientAccess(boolean owner, UUID assignedStoreId) {

    /** System access (other modules, imports): every client, no store rule. */
    public static final ClientAccess SYSTEM = new ClientAccess(true, null);

    public static ClientAccess ofOwner() {
        return SYSTEM;
    }

    public static ClientAccess member(UUID storeId) {
        return new ClientAccess(false, storeId);
    }
}
