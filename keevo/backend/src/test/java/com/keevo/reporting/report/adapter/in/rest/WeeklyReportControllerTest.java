package com.keevo.reporting.report.adapter.in.rest;

import com.keevo.commerce.sale.domain.port.out.DayClosureRepository;
import com.keevo.reporting.report.domain.model.DeliveryStatus;
import com.keevo.reporting.report.domain.model.EndOfDayReport;
import com.keevo.reporting.report.domain.model.ReportType;
import com.keevo.reporting.report.domain.port.in.GenerateEndOfDayReportUseCase;
import com.keevo.reporting.report.domain.port.in.GenerateWeeklyReportUseCase;
import com.keevo.reporting.report.domain.port.in.GetReportHistoryUseCase;
import com.keevo.reporting.report.domain.port.in.ResendReportUseCase;
import com.keevo.shared.infrastructure.web.GlobalExceptionHandler;
import com.keevo.store.store.domain.model.Store;
import com.keevo.store.store.domain.model.StoreType;
import com.keevo.store.store.domain.port.out.StoreRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * WeeklyReportControllerTest — TDD RED tests for POST /api/v1/reports/trigger-weekly.
 * Story 7.3 — Task 4.3.
 *
 * Uses explicit constructor setup (not @InjectMocks) to isolate new endpoint tests.
 */
@ExtendWith(MockitoExtension.class)
class WeeklyReportControllerTest {

    @Mock private GetReportHistoryUseCase getReportHistoryUseCase;
    @Mock private ResendReportUseCase resendReportUseCase;
    @Mock private GenerateWeeklyReportUseCase weeklyReportGenerator;
    @Mock private GenerateEndOfDayReportUseCase dailyReportGenerator;
    @Mock private StoreRepository storeRepository;
    @Mock private DayClosureRepository dayClosureRepository;
    @Mock private com.keevo.reporting.report.domain.port.out.EndOfDayReportRepository reportRepository;

    private MockMvc mockMvc;
    private UUID ownerId;

    @BeforeEach
    void setUp() {
        mockMvc = mvcWithClock(java.time.Clock.systemUTC());
        ownerId = UUID.randomUUID();
    }

    private MockMvc mvcWithClock(java.time.Clock clock) {
        ReportController controller = new ReportController(
                getReportHistoryUseCase, resendReportUseCase, weeklyReportGenerator,
                dailyReportGenerator, storeRepository, dayClosureRepository,
                new com.keevo.reporting.report.application.service.WeeklyReportWindowResolver(reportRepository),
                reportRepository, clock);
        return MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    private void authenticateAs(String role) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        ownerId, null,
                        List.of(new SimpleGrantedAuthority("ROLE_" + role))));
    }

    private Store sampleStore() {
        return new Store(UUID.randomUUID(), "Boutique Test", StoreType.STORE,
                "Yaoundé", null, true, Instant.now(), Instant.now());
    }

    private EndOfDayReport sampleReport() {
        return EndOfDayReport.createNew(
                "kv_test01", UUID.randomUUID(), "Boutique Test",
                null,
                null,  // actorName
                ReportType.WEEKLY, LocalDate.now(), "Rapport hebdo",
                200000, 10, true);
    }

    @Test
    void POST_triggerWeekly_returns200_forOwner() throws Exception {
        authenticateAs("OWNER");
        when(storeRepository.findAllActive()).thenReturn(List.of(sampleStore()));
        when(weeklyReportGenerator.generateWeeklyReport(any())).thenReturn(sampleReport());

        mockMvc.perform(post("/api/v1/reports/trigger-weekly"))
                .andExpect(status().isOk());

        verify(weeklyReportGenerator, atLeastOnce()).generateWeeklyReport(any());
    }

    @Test
    void POST_triggerWeekly_returns403_forEmployee() throws Exception {
        authenticateAs("EMPLOYEE");

        mockMvc.perform(post("/api/v1/reports/trigger-weekly"))
                .andExpect(status().isForbidden());

        verifyNoInteractions(weeklyReportGenerator);
    }

    @Test
    void POST_triggerWeekly_returns401_whenNotAuthenticated() throws Exception {
        SecurityContextHolder.clearContext();

        mockMvc.perform(post("/api/v1/reports/trigger-weekly"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void POST_triggerWeekly_triggersEachActiveStore() throws Exception {
        authenticateAs("OWNER");
        Store store1 = sampleStore();
        Store store2 = sampleStore();
        when(storeRepository.findAllActive()).thenReturn(List.of(store1, store2));
        when(weeklyReportGenerator.generateWeeklyReport(any())).thenReturn(sampleReport());

        mockMvc.perform(post("/api/v1/reports/trigger-weekly"))
                .andExpect(status().isOk());

        verify(weeklyReportGenerator, times(2)).generateWeeklyReport(any());
    }

    @Test
    void POST_triggerWeekly_commandHasIsAutomaticFalse() throws Exception {
        authenticateAs("OWNER");
        when(storeRepository.findAllActive()).thenReturn(List.of(sampleStore()));
        when(weeklyReportGenerator.generateWeeklyReport(any())).thenReturn(sampleReport());

        mockMvc.perform(post("/api/v1/reports/trigger-weekly"))
                .andExpect(status().isOk());

        var captor = org.mockito.ArgumentCaptor.forClass(GenerateWeeklyReportUseCase.WeeklyReportCommand.class);
        verify(weeklyReportGenerator).generateWeeklyReport(captor.capture());
        org.assertj.core.api.Assertions.assertThat(captor.getValue().isAutomatic()).isFalse();
    }
    @Test
    void POST_triggerWeekly_usesInjectedClock() throws Exception {
        // v1s-13-11 follow-up: Sunday 2026-10-11 21:00 WAT (20:00 UTC) — generated on the
        // Sunday, so the window ends at the (injected) generation time.
        Instant fixedNow = Instant.parse("2026-10-11T20:00:00Z");
        mockMvc = mvcWithClock(java.time.Clock.fixed(fixedNow, java.time.ZoneOffset.UTC));
        authenticateAs("OWNER");
        when(storeRepository.findAllActive()).thenReturn(List.of(sampleStore()));
        when(weeklyReportGenerator.generateWeeklyReport(any())).thenReturn(sampleReport());

        mockMvc.perform(post("/api/v1/reports/trigger-weekly"))
                .andExpect(status().isOk());

        var captor = org.mockito.ArgumentCaptor.forClass(GenerateWeeklyReportUseCase.WeeklyReportCommand.class);
        verify(weeklyReportGenerator).generateWeeklyReport(captor.capture());
        // v1s-13-13: anchored on now − commit-safety margin (5 s).
        Instant anchor = fixedNow.minusSeconds(5);
        org.assertj.core.api.Assertions.assertThat(captor.getValue().weekEnd()).isEqualTo(anchor);
        org.assertj.core.api.Assertions.assertThat(captor.getValue().receivedUntil()).isEqualTo(anchor);
    }
    @Test
    void POST_triggerWeekly_skipsStoresWhoseWeekIsAlreadyGenerated() throws Exception {
        // v1s-13-11 follow-up: a second report for the same week would re-count its sales.
        Instant fixedNow = Instant.parse("2026-10-11T20:00:00Z");   // Sunday 21:00 WAT
        mockMvc = mvcWithClock(java.time.Clock.fixed(fixedNow, java.time.ZoneOffset.UTC));
        authenticateAs("OWNER");
        Store done = sampleStore();
        Store pending = sampleStore();
        when(storeRepository.findAllActive()).thenReturn(List.of(done, pending));
        EndOfDayReport existing = mock(EndOfDayReport.class);
        when(existing.getStoreId()).thenReturn(done.id());
        when(reportRepository.findByDateAndTenant(eq(LocalDate.of(2026, 10, 11)), any(), eq(ReportType.WEEKLY)))
                .thenReturn(List.of(existing));
        when(weeklyReportGenerator.generateWeeklyReport(any())).thenReturn(sampleReport());

        mockMvc.perform(post("/api/v1/reports/trigger-weekly"))
                .andExpect(status().isOk());

        var captor = org.mockito.ArgumentCaptor.forClass(GenerateWeeklyReportUseCase.WeeklyReportCommand.class);
        verify(weeklyReportGenerator, times(1)).generateWeeklyReport(captor.capture());
        org.assertj.core.api.Assertions.assertThat(captor.getValue().storeId()).isEqualTo(pending.id());
    }
}
