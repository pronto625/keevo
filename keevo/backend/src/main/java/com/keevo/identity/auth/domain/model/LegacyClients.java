package com.keevo.identity.auth.domain.model;

import com.keevo.identity.api.Role;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;

/**
 * Story 19.10 — the roles a legacy app may not open. A legacy client is an app that does not declare its version
 * (the Flutter V1 2.0.1 sends no {@code X-App-Version} header — the adapter turns that into a plain flag, the domain
 * never reads headers). V1 knows only OWNER and EMPLOYEE: a MANAGER would see a seller UI, a VIEWER would lose its
 * offline sales rejected at sync. So for a legacy client MANAGER and VIEWER memberships are hidden from the login lists
 * (V1 then shows its own « Votre accès a été désactivé… » when nothing is left) and refused at select-tenant and
 * refresh ({@code APP_UPDATE_REQUIRED}, 403: V1 ends the session). OWNER and EMPLOYEE are never affected. Pure Java.
 */
public final class LegacyClients {

    private LegacyClients() {
    }

    /** Whether a legacy client may not open a membership of this role: MANAGER or VIEWER. */
    public static boolean refuses(String role) {
        Role r = Role.parse(role);
        return r == Role.MANAGER || r == Role.VIEWER;
    }

    /**
     * Refuses the session of a MANAGER / VIEWER membership to a legacy client, before any token is issued.
     *
     * @throws DomainException APP_UPDATE_REQUIRED (403) « Ce rôle nécessite la dernière version de l'application :
     *                         mettez-la à jour »
     */
    public static void requireAllowed(boolean legacyClient, String role) {
        if (legacyClient && refuses(role)) {
            throw new DomainException(ErrorCode.APP_UPDATE_REQUIRED);
        }
    }
}
