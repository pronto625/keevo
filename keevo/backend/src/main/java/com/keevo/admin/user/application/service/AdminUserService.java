package com.keevo.admin.user.application.service;

import com.keevo.admin.crosstenant.AdminParams;
import com.keevo.admin.crosstenant.CrossTenantSql;
import com.keevo.admin.crosstenant.CursorPage;
import com.keevo.admin.platformrevenue.domain.port.out.PlatformRevenuePort;
import com.keevo.admin.platformrevenue.domain.port.out.PlatformRevenuePort.Bucket;
import com.keevo.admin.platformrevenue.domain.port.out.PlatformRevenuePort.Totals;
import com.keevo.admin.user.domain.model.AdminUserViews;
import com.keevo.admin.user.domain.model.AdminUserViews.DeviceSession;
import com.keevo.admin.user.domain.model.AdminUserViews.TopPerformer;
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
import java.util.Comparator;
import java.util.List;
import java.util.Map;
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

    static final int TOP_PERFORMERS = 5;

    private final AdminUserReadPort reader;
    private final Clock clock;
    private final CrossTenantSql crossTenant;
    private final PlatformRevenuePort revenue;

    public AdminUserService(AdminUserReadPort reader, Clock clock, CrossTenantSql crossTenant,
                            PlatformRevenuePort revenue) {
        this.reader = reader;
        this.clock = clock;
        this.crossTenant = crossTenant;
        this.revenue = revenue;
    }

    @Override
    public CursorPage<UserItem> listUsers(UserQuery q) {
        String role = role(q.role());
        Boolean active = AdminParams.flag(q.active(), "Le filtre « actif »");
        String tenantId = q.tenantId() == null || q.tenantId().isBlank() ? null
                : crossTenant.requireTenant(q.tenantId()).id(); // 422 malformed, 404 unknown / deleted account
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
        // Sellers of the period from the single revenue source (20.9), chunked across tenants, summed per person.
        Map<String, Totals> sellers = revenue.totals(crossTenant.liveTenants(), criteria.newFrom(), criteria.newTo(),
                Bucket.SELLER);
        long sellerRevenue = sellers.values().stream().mapToLong(Totals::revenue).sum();
        long average = sellers.isEmpty() ? 0 : Math.round((double) sellerRevenue / sellers.size());
        List<Map.Entry<String, Totals>> best = sellers.entrySet().stream()
                .sorted(Comparator.<Map.Entry<String, Totals>>comparingLong(e -> e.getValue().revenue()).reversed()
                        .thenComparing(Map.Entry::getKey))
                .limit(TOP_PERFORMERS).toList();
        Map<String, String> names = reader.displayNames(best.stream().map(Map.Entry::getKey).toList());
        List<TopPerformer> top = best.stream().map(e -> new TopPerformer(e.getKey(),
                names.getOrDefault(e.getKey(), AdminUserViews.UNNAMED_USER), e.getValue().documents(),
                e.getValue().revenue())).toList();
        return new UserStats(totals.total(), totals.active(), totals.created(), from, to, reader.byRole(criteria),
                average, top);
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
