package com.keevo.admin.user.domain.port.in;

import com.keevo.admin.crosstenant.CursorPage;
import com.keevo.admin.user.domain.model.AdminUserViews.DeviceSession;
import com.keevo.admin.user.domain.model.AdminUserViews.UserItem;
import com.keevo.admin.user.domain.model.AdminUserViews.UserStats;

import java.util.List;

/**
 * Story 20.13 — the platform users read model of the super-admin (Bref {@code users} admin routes). Raw request
 * values; the service validates them (422 VALIDATION_ERROR with a French message).
 */
public interface AdminUserUseCase {

    record UserQuery(String role, String active, String tenantId, String search, String cursor, String limit) {
    }

    record StatsQuery(String from, String to, String role, String active) {
    }

    CursorPage<UserItem> listUsers(UserQuery query);

    UserStats getStats(StatsQuery query);

    UserItem getUser(String userId);

    List<DeviceSession> listDevices(String userId);
}
