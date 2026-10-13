package com.keevo.identity.employee.application.service;

import com.keevo.identity.employee.domain.model.Employee;
import com.keevo.identity.employee.domain.model.EmployeeActivity;
import com.keevo.identity.employee.domain.model.EmployeeStatus;
import com.keevo.identity.employee.domain.port.out.EmployeeActivityRepository;
import com.keevo.identity.employee.domain.port.out.EmployeeActivityRepository.RawActivity;
import com.keevo.identity.employee.domain.port.out.EmployeeRepository;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import com.keevo.shared.infrastructure.persistence.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("GetEmployeeActivityService (v1s-14-6, FR67)")
class GetEmployeeActivityServiceTest {

    /** Wednesday 2026-10-14 12:00 WAT (11:00 UTC). */
    private static final Instant NOW = Instant.parse("2026-10-14T11:00:00Z");

    @Mock EmployeeRepository employeeRepository;
    @Mock EmployeeActivityRepository activityRepository;

    private GetEmployeeActivityService service;

    @BeforeEach
    void setUp() {
        service = new GetEmployeeActivityService(employeeRepository, activityRepository,
                Clock.fixed(NOW, ZoneOffset.UTC));
        TenantContext.setCurrentTenant("kv_test");
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private static Employee employee(EmployeeStatus status, Instant createdAt) {
        return new Employee(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "Awa", "Ndiaye", status, false, createdAt);
    }

    private void stubRaw(Employee e, RawActivity raw) {
        when(activityRepository.findRawActivity(anyCollection(), any(), any(), any(), any()))
                .thenReturn(raw == null ? Map.of() : Map.of(e.getUserId(), raw));
    }

    private static RawActivity raw(UUID userId, Instant lastAudit, Instant lastSale) {
        return new RawActivity(userId, null, lastAudit, lastSale, 0, 0L, 0, 0L, false);
    }

    @Test
    @DisplayName("WAT windows: today = 00:00 WAT, week = Monday 00:00 WAT, tenant schema forwarded")
    void watWindows() {
        Employee e = employee(EmployeeStatus.ACTIVE, NOW.minus(Duration.ofDays(30)));
        when(employeeRepository.findById(e.getId())).thenReturn(Optional.of(e));
        stubRaw(e, null);

        service.forEmployee(e.getId());

        verify(activityRepository).findRawActivity(eq(List.of(e.getUserId())),
                eq(Instant.parse("2026-10-13T23:00:00Z")),   // 14 Oct 00:00 WAT
                eq(Instant.parse("2026-10-11T23:00:00Z")),   // Monday 12 Oct 00:00 WAT
                eq(NOW), eq("kv_test"));
    }

    @Test
    @DisplayName("sales figures, last login and session are passed through")
    void figuresPassedThrough() {
        Employee e = employee(EmployeeStatus.ACTIVE, NOW.minus(Duration.ofDays(30)));
        Instant login = NOW.minus(Duration.ofDays(1));
        Instant sale = NOW.minus(Duration.ofHours(1));
        when(employeeRepository.findById(e.getId())).thenReturn(Optional.of(e));
        stubRaw(e, new RawActivity(e.getUserId(), login, login, sale, 2, 10_000L, 3, 13_000L, true));

        EmployeeActivity a = service.forEmployee(e.getId());

        assertThat(a.employeeId()).isEqualTo(e.getId());
        assertThat(a.todaySalesCount()).isEqualTo(2);
        assertThat(a.todaySalesAmount()).isEqualTo(10_000L);
        assertThat(a.weekSalesCount()).isEqualTo(3);
        assertThat(a.weekSalesAmount()).isEqualTo(13_000L);
        assertThat(a.lastLoginAt()).isEqualTo(login);
        assertThat(a.lastActivityAt()).as("greatest of audit and sale").isEqualTo(sale);
        assertThat(a.activeSession()).isTrue();
        assertThat(a.inactive()).isFalse();
    }

    @Test
    @DisplayName("ACTIVE employee idle for 49 h → inactive")
    void shouldFlagEmployeeInactiveAfter48h() {
        Employee e = employee(EmployeeStatus.ACTIVE, NOW.minus(Duration.ofDays(30)));
        when(employeeRepository.findById(e.getId())).thenReturn(Optional.of(e));
        stubRaw(e, raw(e.getUserId(), NOW.minus(Duration.ofHours(50)), NOW.minus(Duration.ofHours(49))));

        EmployeeActivity a = service.forEmployee(e.getId());

        assertThat(a.inactive()).isTrue();
        assertThat(a.lastActivityAt()).isEqualTo(NOW.minus(Duration.ofHours(49)));
    }

    @Test
    @DisplayName("ACTIVE employee active 47 h ago → not inactive")
    void recentActivityNotInactive() {
        Employee e = employee(EmployeeStatus.ACTIVE, NOW.minus(Duration.ofDays(30)));
        when(employeeRepository.findById(e.getId())).thenReturn(Optional.of(e));
        stubRaw(e, raw(e.getUserId(), NOW.minus(Duration.ofHours(47)), null));

        assertThat(service.forEmployee(e.getId()).inactive()).isFalse();
    }

    @Test
    @DisplayName("fresh employee (created 2 h ago, no activity) → not inactive, lastActivityAt null")
    void freshEmployee() {
        Employee e = employee(EmployeeStatus.ACTIVE, NOW.minus(Duration.ofHours(2)));
        when(employeeRepository.findById(e.getId())).thenReturn(Optional.of(e));
        stubRaw(e, null);

        EmployeeActivity a = service.forEmployee(e.getId());

        assertThat(a.inactive()).isFalse();
        assertThat(a.lastActivityAt()).isNull();
        assertThat(a.lastLoginAt()).isNull();
        assertThat(a.todaySalesCount()).isZero();
    }

    @Test
    @DisplayName("ACTIVE employee created 3 days ago, never active → inactive")
    void neverActiveOldEmployee() {
        Employee e = employee(EmployeeStatus.ACTIVE, NOW.minus(Duration.ofDays(3)));
        when(employeeRepository.findById(e.getId())).thenReturn(Optional.of(e));
        stubRaw(e, null);

        assertThat(service.forEmployee(e.getId()).inactive()).isTrue();
    }

    @Test
    @DisplayName("deactivated employee idle 10 days → never inactive")
    void deactivatedNeverInactive() {
        Employee e = employee(EmployeeStatus.INACTIVE, NOW.minus(Duration.ofDays(60)));
        when(employeeRepository.findById(e.getId())).thenReturn(Optional.of(e));
        stubRaw(e, raw(e.getUserId(), NOW.minus(Duration.ofDays(10)), null));

        assertThat(service.forEmployee(e.getId()).inactive()).isFalse();
    }

    @Test
    @DisplayName("unknown employee → EMPLOYEE_NOT_FOUND, repository never queried")
    void unknownEmployee() {
        UUID id = UUID.randomUUID();
        when(employeeRepository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.forEmployee(id))
                .isInstanceOf(DomainException.class)
                .extracting(ex -> ((DomainException) ex).getErrorCode())
                .isEqualTo(ErrorCode.EMPLOYEE_NOT_FOUND);
        verifyNoInteractions(activityRepository);
    }

    @Test
    @DisplayName("forAll → one entry per employee row, in list order")
    void forAll() {
        Employee a = employee(EmployeeStatus.ACTIVE, NOW.minus(Duration.ofDays(1)));
        Employee b = employee(EmployeeStatus.INACTIVE, NOW.minus(Duration.ofDays(9)));
        when(employeeRepository.findAllOrderByStatusAndCreatedAt()).thenReturn(List.of(a, b));
        when(activityRepository.findRawActivity(anyCollection(), any(), any(), any(), any()))
                .thenReturn(Map.of());

        List<EmployeeActivity> all = service.forAll();

        assertThat(all).extracting(EmployeeActivity::employeeId).containsExactly(a.getId(), b.getId());
    }

    @Test
    @DisplayName("forAll with no employee → empty, repository never queried")
    void forAllEmpty() {
        when(employeeRepository.findAllOrderByStatusAndCreatedAt()).thenReturn(List.of());
        assertThat(service.forAll()).isEmpty();
        verifyNoInteractions(activityRepository);
    }
}
