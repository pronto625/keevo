package com.keevo.admin.user.application.service;

import com.keevo.admin.crosstenant.CursorPage;
import com.keevo.admin.user.domain.model.AdminUserViews;
import com.keevo.admin.user.domain.model.AdminUserViews.RoleCount;
import com.keevo.admin.user.domain.model.AdminUserViews.UserItem;
import com.keevo.admin.user.domain.model.AdminUserViews.UserStats;
import com.keevo.admin.user.domain.port.in.AdminUserUseCase.StatsQuery;
import com.keevo.admin.user.domain.port.in.AdminUserUseCase.UserQuery;
import com.keevo.admin.user.domain.port.out.AdminUserReadPort;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("20.13 — platform users service (Bref users admin routes)")
class AdminUserServiceTest {

    /** 2026-11-08 00:30 WAT (still the 7th in UTC). */
    private static final Instant NOW = Instant.parse("2026-11-07T23:30:00Z");
    private static final String USER = "0b6e3f1c-8a8d-4c51-9a0e-1d2f3a4b5c6d";

    @Mock AdminUserReadPort reader;

    AdminUserService service;

    @BeforeEach
    void setUp() {
        service = new AdminUserService(reader, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    @DisplayName("list: role any case, active flag, search, limit; cursor bound to the filters")
    void listCriteria() {
        when(reader.users(any())).thenReturn(new CursorPage<>(List.of(), null, false));

        service.listUsers(new UserQuery("owner", "true", null, " awa ", null, "10"));

        ArgumentCaptor<AdminUserReadPort.UserCriteria> c = ArgumentCaptor.forClass(AdminUserReadPort.UserCriteria.class);
        verify(reader).users(c.capture());
        assertThat(c.getValue().role()).isEqualTo("OWNER");
        assertThat(c.getValue().active()).isTrue();
        assertThat(c.getValue().search()).isEqualTo("awa");
        assertThat(c.getValue().limit()).isEqualTo(10);
        assertThat(c.getValue().filters()).contains("OWNER", "true", "awa");
    }

    @Test
    @DisplayName("bad role / flag / account → 400")
    void invalidParams() {
        assertCode(() -> service.listUsers(new UserQuery("BOSS", null, null, null, null, null)), ErrorCode.VALIDATION_ERROR);
        assertCode(() -> service.listUsers(new UserQuery(null, "yes", null, null, null, null)), ErrorCode.VALIDATION_ERROR);
        assertCode(() -> service.listUsers(new UserQuery(null, null, "x", null, null, null)), ErrorCode.VALIDATION_ERROR);
        assertCode(() -> service.getStats(new StatsQuery(null, null, "SUPER_ADMIN", null)), ErrorCode.VALIDATION_ERROR);
        assertCode(() -> service.getStats(new StatsQuery("2025-01-01", "2026-11-08", null, null)),
                ErrorCode.VALIDATION_ERROR);
        verifyNoInteractions(reader);
    }

    @Test
    @DisplayName("stats: default window = current WAT month up to today, totals and roles from the port")
    void statsDefaultWindow() {
        when(reader.totals(any())).thenReturn(new AdminUserReadPort.Totals(12, 10, 3));
        when(reader.byRole(any())).thenReturn(List.of(new RoleCount("OWNER", "Propriétaire", 5)));

        UserStats stats = service.getStats(new StatsQuery(null, null, null, null));

        assertThat(stats.from()).isEqualTo(LocalDate.of(2026, 11, 1));
        assertThat(stats.to()).isEqualTo(LocalDate.of(2026, 11, 8));
        assertThat(stats.newUsers()).isEqualTo(3);
        assertThat(stats.byRole()).extracting(RoleCount::label).containsExactly("Propriétaire");
        ArgumentCaptor<AdminUserReadPort.StatsCriteria> c = ArgumentCaptor.forClass(AdminUserReadPort.StatsCriteria.class);
        verify(reader).totals(c.capture());
        assertThat(c.getValue().newFrom()).isEqualTo(Instant.parse("2026-10-31T23:00:00Z"));
        assertThat(c.getValue().newTo()).isEqualTo(Instant.parse("2026-11-08T23:00:00Z"));
    }

    @Test
    @DisplayName("detail / devices: unknown or malformed user → 404, devices never read")
    void unknownUser() {
        when(reader.user(USER)).thenReturn(Optional.empty());

        assertCode(() -> service.getUser(USER), ErrorCode.USER_NOT_FOUND);
        assertCode(() -> service.listDevices(USER), ErrorCode.USER_NOT_FOUND);
        assertCode(() -> service.getUser("nope"), ErrorCode.VALIDATION_ERROR);
        verify(reader, never()).devices(anyString());
    }

    @Test
    @DisplayName("devices of a known user come from the port")
    void devices() {
        UserItem user = new UserItem(USER, "Awa", null, null, true, false, List.of(), 1, null, null);
        when(reader.user(USER)).thenReturn(Optional.of(user));
        when(reader.devices(USER)).thenReturn(List.of(new AdminUserViews.DeviceSession("s", "Redmi", null,
                AdminUserViews.PLATFORM, null)));

        assertThat(service.listDevices(USER)).hasSize(1);
        assertThat(AdminUserViews.roleLabel("MANAGER")).isEqualTo("Gérant");
        assertThat(AdminUserViews.roleLabel("SUPER_ADMIN")).isEqualTo("Super-administrateur");
    }

    private static void assertCode(Runnable call, ErrorCode code) {
        assertThatThrownBy(call::run).isInstanceOf(DomainException.class)
                .extracting(e -> ((DomainException) e).getErrorCode()).isEqualTo(code);
    }
}
