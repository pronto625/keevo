package com.keevo.admin.user.domain.model;

import com.keevo.admin.AdminLabels;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * Story 20.13 — read models of the super-admin users API (Bref {@code GET /users}, {@code /users/stats},
 * {@code /users/:id}, {@code /users/:id/devices}). A user is a person ({@code public.users}); their accounts and roles
 * are their memberships. Codes come with their French label.
 */
public final class AdminUserViews {

    /** Display name of a user without first / last name. */
    public static final String UNNAMED_USER = "Utilisateur sans nom";
    /** Device label of a session opened without one (same label as 16.11). */
    public static final String UNKNOWN_DEVICE = "Appareil inconnu";
    /** Account label of a super-admin console session (no tenant). */
    public static final String PLATFORM = "Console super-admin";

    private AdminUserViews() {
    }

    public record Membership(String tenantId, String tenantName, String role, String roleLabel, boolean active) {
    }

    public record UserItem(String id, String displayName, String phoneNumber, String email, boolean active,
                           boolean superAdmin, List<Membership> memberships, long deviceCount, Instant lastSeenAt,
                           Instant createdAt) {
        public UserItem {
            memberships = List.copyOf(memberships);
        }
    }

    public record RoleCount(String role, String label, long count) {
    }

    /** {@code newUsers} = accounts created between {@code from} and {@code to} (inclusive, Africa/Douala). */
    public record UserStats(long totalUsers, long activeUsers, long newUsers, LocalDate from, LocalDate to,
                            List<RoleCount> byRole) {
        public UserStats {
            byRole = List.copyOf(byRole);
        }
    }

    public record DeviceSession(String sessionId, String deviceLabel, String tenantId, String tenantName,
                                Instant lastUsedAt) {
    }

    /** French label of a role name ({@code OWNER}, {@code MANAGER}, {@code EMPLOYEE}, {@code VIEWER}, {@code SUPER_ADMIN}). */
    public static String roleLabel(String role) {
        if (role == null) {
            return AdminLabels.UNKNOWN_USER;
        }
        return switch (role) {
            case "OWNER" -> AdminLabels.OWNER;
            case "MANAGER" -> AdminLabels.MANAGER;
            case "EMPLOYEE" -> AdminLabels.EMPLOYEE;
            case "VIEWER" -> AdminLabels.VIEWER;
            case "SUPER_ADMIN" -> "Super-administrateur";
            default -> "Autre rôle";
        };
    }
}
