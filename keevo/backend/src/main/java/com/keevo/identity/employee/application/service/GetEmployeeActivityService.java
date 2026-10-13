package com.keevo.identity.employee.application.service;

import com.keevo.identity.employee.domain.model.Employee;
import com.keevo.identity.employee.domain.model.EmployeeActivity;
import com.keevo.identity.employee.domain.model.EmployeeStatus;
import com.keevo.identity.employee.domain.port.in.GetEmployeeActivityUseCase;
import com.keevo.identity.employee.domain.port.out.EmployeeActivityRepository;
import com.keevo.identity.employee.domain.port.out.EmployeeActivityRepository.RawActivity;
import com.keevo.identity.employee.domain.port.out.EmployeeRepository;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import com.keevo.shared.infrastructure.persistence.TenantContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * GetEmployeeActivityService — FR67 employee activity (v1s-14-6).
 *
 * <p>Windows in WAT ({@code Africa/Lagos}) from the injected {@link Clock}: today =
 * [today 00:00 WAT, now], week = [Monday 00:00 WAT, now]. An ACTIVE employee is {@code inactive}
 * when its last activity (or, without any, its creation) is more than 48 h old; a deactivated
 * employee never is.
 */
@Service
@Transactional(readOnly = true)
public class GetEmployeeActivityService implements GetEmployeeActivityUseCase {

    /** Shop time zone — same convention as JdbcSellerLeaderboardRepository. */
    static final ZoneId WAT = ZoneId.of("Africa/Lagos");

    static final Duration INACTIVITY_THRESHOLD = Duration.ofHours(48);

    private final EmployeeRepository employeeRepository;
    private final EmployeeActivityRepository activityRepository;
    private final Clock clock;

    public GetEmployeeActivityService(EmployeeRepository employeeRepository,
                                      EmployeeActivityRepository activityRepository,
                                      Clock clock) {
        this.employeeRepository = employeeRepository;
        this.activityRepository = activityRepository;
        this.clock = clock;
    }

    @Override
    public EmployeeActivity forEmployee(UUID employeeId) {
        Employee employee = employeeRepository.findById(employeeId)
                .orElseThrow(() -> new DomainException(ErrorCode.EMPLOYEE_NOT_FOUND));
        return compute(List.of(employee)).get(0);
    }

    @Override
    public List<EmployeeActivity> forAll() {
        return compute(employeeRepository.findAllOrderByStatusAndCreatedAt());
    }

    private List<EmployeeActivity> compute(List<Employee> employees) {
        if (employees.isEmpty()) {
            return List.of();
        }
        Instant now = clock.instant();
        LocalDate today = now.atZone(WAT).toLocalDate();
        Instant todayStart = today.atStartOfDay(WAT).toInstant();
        Instant weekStart = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                .atStartOfDay(WAT).toInstant();

        Map<UUID, RawActivity> raw = activityRepository.findRawActivity(
                employees.stream().map(Employee::getUserId).distinct().toList(),
                todayStart, weekStart, now, TenantContext.getCurrentTenant());

        return employees.stream()
                .map(e -> toActivity(e, raw.getOrDefault(e.getUserId(), RawActivity.empty(e.getUserId())), now))
                .toList();
    }

    private static EmployeeActivity toActivity(Employee e, RawActivity r, Instant now) {
        Instant lastActivity = latest(r.lastAuditAt(), r.lastSaleAt());
        Instant reference = lastActivity != null ? lastActivity : e.getCreatedAt();
        boolean inactive = e.getStatus() == EmployeeStatus.ACTIVE
                && reference.isBefore(now.minus(INACTIVITY_THRESHOLD));
        return new EmployeeActivity(
                e.getId(), e.getUserId(), r.lastLoginAt(), lastActivity,
                r.todayCount(), r.todayAmount(), r.weekCount(), r.weekAmount(),
                r.activeSession(), inactive);
    }

    private static Instant latest(Instant a, Instant b) {
        if (a == null) return b;
        if (b == null) return a;
        return a.isAfter(b) ? a : b;
    }
}
