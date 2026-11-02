package com.keevo.identity.onboarding.domain.port.in;

/**
 * UpdateLeaderboardVisibilityCommand — Story 17.2.
 *
 * @param visibleToEmployees {@code true} = employees see the anonymised leaderboard of their own store
 */
public record UpdateLeaderboardVisibilityCommand(boolean visibleToEmployees) {}
