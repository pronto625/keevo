package com.keevo.admin.user.application.service;

import com.keevo.admin.crosstenant.AdminParams;
import com.keevo.admin.crosstenant.CrossTenantSql;
import com.keevo.admin.crosstenant.CursorPage;
import com.keevo.admin.user.domain.model.AdminUserViews.DeviceSession;
import com.keevo.admin.user.domain.model.AdminUserViews.UserItem;
import com.keevo.admin.user.domain.model.AdminUserViews.UserStats;
import com.keevo.admin.user.domain.port.in.AdminUserUseCase;
import com.keevo.admin.user.domain.port.out.AdminUserReadPort;
import com.keevo.identity.api.Role;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;

/**
 * Story 20.13 — the platform users of the super-admin (Bref {@code GET /users}, {@code /users/stats},
 * {@code /users/:id}, {@code /users/:id/devices}). Validates the raw parameters, defaults the stats window to the
 * current month (Africa/Douala) and delegates to the public-table read port. Read-only.
 */
@Service
public class AdminUserService implements AdminUserUseCase {

    static final ZoneId WAT = ZoneId.of("Africa/Douala");
    static final int MAX_STATS_DAYS = 366;
    static final String USER_NOT_FOUND = "Utilisateur introuvable";

    private final AdminUserReadPort reader;
    private final Clock clock;

    public AdminUserService(AdminUserReadPort reader, Clock clock) {
        this.reader = reader;
        this.clock = clock;
    }

    @Override
    public CursorPage<UserItem> listUsers(UserQuery q) {
        String role = role(q.role());
        Boolean active = AdminParams.flag(q.active(), "Le filtre « actif »");
        String tenantId = CrossTenantSql.requireUuid(q.tenantId(), CrossTenantSql.INVALID_TENANT);
        String search = AdminParams.search(q.search());
        int limit = AdminParams.limit(q.limit());
        String filters = "users|" + role + "|" + active + "|" + tenantId + "|" + search;
        return reader.users(new AdminUserReadPort.UserCriteria(role, active, tenantId, search, q.cursor(), filters,
                limit));
    }

    @Override
    public UserStats getStats(StatsQuery q) {
        LocalDate today = LocalDate.now(clock.withZone(WAT));
        LocalDate from = AdminParams.date(q.from(), "La date de début");
        LocalDate to = AdminParams.date(q.to(), "La date de fin");
        if (from == null) {
            from = (to == null ? today : to).withDayOfMonth(1);
        }
        if (to == null) {
            to = today.isBefore(from) ? from : today;
        }
        AdminParams.range(from, to);
        if (ChronoUnit.DAYS.between(from, to) + 1 > MAX_STATS_DAYS) {
            throw AdminParams.invalid("La période ne peut pas dépasser " + MAX_STATS_DAYS + " jours");
        }
        String role = role(q.role());
        if (Role.SUPER_ADMIN.name().equals(role)) {
            throw AdminParams.invalid("Les statistiques portent sur les utilisateurs des comptes, pas sur les "
                    + "super-administrateurs");
        }
        Boolean active = AdminParams.flag(q.active(), "Le filtre « actif »");
        AdminUserReadPort.StatsCriteria criteria = new AdminUserReadPort.StatsCriteria(role, active,
                from.atStartOfDay(WAT).toInstant(), to.plusDays(1).atStartOfDay(WAT).toInstant());
        AdminUserReadPort.Totals totals = reader.totals(criteria);
        return new UserStats(totals.total(), totals.active(), totals.created(), from, to, reader.byRole(criteria));
    }

    @Override
    public UserItem getUser(String userId) {
        return reader.user(requireUser(userId))
                .orElseThrow(() -> DomainException.userFacing(ErrorCode.USER_NOT_FOUND, USER_NOT_FOUND));
    }

    @Override
    public List<DeviceSession> listDevices(String userId) {
        String id = requireUser(userId);
        if (reader.user(id).isEmpty()) {
            throw DomainException.userFacing(ErrorCode.USER_NOT_FOUND, USER_NOT_FOUND);
        }
        return reader.devices(id);
    }

    private static String requireUser(String userId) {
        String id = CrossTenantSql.requireUuid(userId, "Utilisateur invalide");
        if (id == null) {
            throw DomainException.userFacing(ErrorCode.USER_NOT_FOUND, USER_NOT_FOUND);
        }
        return id;
    }

    /** A role name (any case) of {@link Role}; blank → null. */
    static String role(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        Role role = Role.parse(raw.trim().toUpperCase(Locale.ROOT));
        if (role == null) {
            throw AdminParams.invalid("Rôle inconnu : choisissez OWNER, MANAGER, EMPLOYEE, VIEWER ou SUPER_ADMIN");
        }
        return role.name();
    }
}
