package com.keevo.crm.contact.adapter.in;

import com.keevo.crm.contact.application.service.ClientAccess;
import com.keevo.shared.infrastructure.security.CurrentActor;

/**
 * Story 20.2 — the client access of the authenticated actor, read once by the inbound adapters (REST, sync): an OWNER
 * is unrestricted; anyone else is a store member pinned to its assigned store (fail closed: no store = legacy
 * clients only, no creation).
 */
public final class CurrentClientAccess {

    private CurrentClientAccess() {
    }

    public static ClientAccess get() {
        if (CurrentActor.isOwner()) {
            return ClientAccess.SYSTEM;
        }
        return ClientAccess.member(CurrentActor.assignedStoreIdOrNull());
    }
}
