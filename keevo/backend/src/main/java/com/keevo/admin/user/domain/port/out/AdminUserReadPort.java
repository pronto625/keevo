package com.keevo.admin.user.domain.port.out;

import com.keevo.admin.crosstenant.CursorPage;
import com.keevo.admin.user.domain.model.AdminUserViews.DeviceSession;
import com.keevo.admin.user.domain.model.AdminUserViews.RoleCount;
import com.keevo.admin.user.domain.model.AdminUserViews.UserItem;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Story 20.13 — reads of the platform users (public tables only: {@code users}, {@code user_tenant_memberships},
 * {@code tenants}, {@code refresh_tokens}); no tenant schema is read.
 */
public interface AdminUserReadPort {

    /** {@code role}: a membership role or {@code SUPER_ADMIN}; null = any. */
    record UserCriteria(String role, Boolean active, String tenantId, String search, String cursor, String filters,
                        int limit) {
    }

    /** Totals over non-super-admin users; {@code newFrom} / {@code newTo}: half-open creation window. */
    record StatsCriteria(String role, Boolean active, Instant newFrom, Instant newTo) {
    }

    record Totals(long total, long active, long created) {
    }

    CursorPage<UserItem> users(UserCriteria criteria);

    Optional<UserItem> user(String userId);

    Totals totals(StatsCriteria criteria);

    List<RoleCount> byRole(StatsCriteria criteria);

    List<DeviceSession> devices(String userId);

    /** Display name per user id (« Utilisateur sans nom » when none) — one query. */
    Map<String, String> displayNames(Collection<String> userIds);
}
