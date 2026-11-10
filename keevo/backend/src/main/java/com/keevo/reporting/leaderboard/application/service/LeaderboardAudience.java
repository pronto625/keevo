package com.keevo.reporting.leaderboard.application.service;

import com.keevo.identity.api.TenantPreferencesQueryPort;
import com.keevo.identity.api.TenantPreferencesView;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import com.keevo.shared.module.EntitlementService;
import com.keevo.shared.module.ModuleState;

import java.util.UUID;
import java.util.function.Supplier;

/**
 * Story 17.2 rule, extracted by Story 21.8 so the REST leaderboard and the AI tool apply the very same one (Policy
 * object): the OWNER sees the ranking of the requested store (or all stores) with names; anyone else only while the
 * owner shares it with employees and {@code advanced-reports} is fully ENABLED (in the READ_ONLY grace the owner could
 * not turn sharing off), limited to their assigned store (any requested store ignored) and anonymised except their own
 * row. Not shared → 403 {@code FORBIDDEN} « Le classement n'est pas partagé avec les employés. ».
 */
@org.springframework.stereotype.Component
public class LeaderboardAudience {

    public static final String ADVANCED_REPORTS = "advanced-reports";
    public static final String NOT_SHARED_MESSAGE = "Le classement n'est pas partagé avec les employés.";

    /**
     * What the caller sees.
     *
     * @param storeId    the store to rank, {@code null} = all stores (owner only)
     * @param anonymised whether rows other than the caller's own are anonymised
     */
    public record View(UUID storeId, boolean anonymised) {
    }

    private final TenantPreferencesQueryPort preferences;
    private final EntitlementService entitlements;

    public LeaderboardAudience(TenantPreferencesQueryPort preferences, EntitlementService entitlements) {
        this.preferences = preferences;
        this.entitlements = entitlements;
    }

    /**
     * @param owner          whether the caller is the OWNER
     * @param requestedStore the store asked for (owner only; ignored otherwise)
     * @param assignedStore  the caller's assigned store, read only for a non-owner (fails closed without store)
     */
    public View resolve(String tenant, boolean owner, UUID requestedStore, Supplier<UUID> assignedStore) {
        if (owner) {
            return new View(requestedStore, false);
        }
        boolean shared = entitlements.state(tenant, ADVANCED_REPORTS) == ModuleState.ENABLED
                && preferences.find(tenant)
                .map(TenantPreferencesView::leaderboardVisibleToEmployees)
                .orElse(false);
        if (!shared) {
            throw DomainException.userFacing(ErrorCode.FORBIDDEN, NOT_SHARED_MESSAGE);
        }
        return new View(assignedStore.get(), true);
    }
}
