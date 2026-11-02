package com.keevo.identity.onboarding.adapter.in.web;

import jakarta.validation.constraints.NotNull;

/**
 * UpdateLeaderboardVisibilityRequestDto — body of {@code PUT /api/v1/tenant/leaderboard-visibility} (Story 17.2).
 *
 * @param visibleToEmployees required: {@code true} shares the anonymised leaderboard with employees
 */
public record UpdateLeaderboardVisibilityRequestDto(
        @NotNull(message = "Indiquez si le classement est partagé avec les employés")
        Boolean visibleToEmployees
) {}
