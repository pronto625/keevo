package com.keevo.identity.onboarding.domain.port.in;

import com.keevo.identity.onboarding.domain.model.TenantPreferences;

/**
 * UpdateLeaderboardVisibilityUseCase — Story 17.2: the owner shares the seller leaderboard with employees
 * (anonymised, own store) or hides it again. OFF by default.
 *
 * <p>Pure Java — NO Spring/framework imports.
 */
public interface UpdateLeaderboardVisibilityUseCase {

    /**
     * Sets {@code leaderboard_visible_to_employees} for the current tenant. Audited only on a real change.
     *
     * @return the saved preferences
     * @throws com.keevo.shared.domain.exception.DomainException PREFERENCES_NOT_FOUND if no preferences exist
     */
    TenantPreferences update(UpdateLeaderboardVisibilityCommand command);
}
