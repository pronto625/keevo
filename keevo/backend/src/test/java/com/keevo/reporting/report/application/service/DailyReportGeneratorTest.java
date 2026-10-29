package com.keevo.reporting.report.application.service;

import com.keevo.identity.employee.adapter.in.api.IdentityEmployeeApiTestSupport;
import com.keevo.store.store.adapter.in.api.StoreApiTestSupport;
import com.keevo.commerce.api.ClosureWindow;
import com.keevo.shared.capability.notification.WhatsAppSender;
import com.keevo.identity.api.OwnerRecipientResolver;
import com.keevo.identity.auth.adapter.out.persistence.impl.OwnerRecipientResolverAdapter;
import com.keevo.identity.api.Role;
import com.keevo.identity.auth.domain.model.User;
import com.keevo.identity.auth.domain.port.out.UserRepository;
import com.keevo.identity.employee.domain.port.out.EmployeeRepository;
import com.keevo.reporting.report.domain.model.DeliveryStatus;
import com.keevo.reporting.report.domain.model.EndOfDayReport;
import com.keevo.reporting.report.domain.model.EndOfDayReportData;
import com.keevo.reporting.report.domain.model.ReportType;
import com.keevo.reporting.report.domain.port.in.GenerateEndOfDayReportUseCase.GenerateReportCommand;
import com.keevo.reporting.report.domain.port.out.EndOfDayReportRepository;
import com.keevo.store.store.domain.model.Store;
import com.keevo.store.api.StoreType;
import com.keevo.store.store.domain.port.out.StoreRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * DailyReportGeneratorTest — TDD GREEN tests for the concrete daily report generator.
 * Story 7.2 — Task 2.1.
 */
@ExtendWith(MockitoExtension.class)
class DailyReportGeneratorTest {

    // 12.2d: the store / identity / subscription API adapters require an explicit, current tenant schema
    @org.junit.jupiter.api.BeforeEach
    void bindTenantContext() {
        com.keevo.shared.infrastructure.persistence.TenantContext.setCurrentTenant("kv_test01");
    }

    @org.junit.jupiter.api.AfterEach
    void clearTenantContext12d() {
        com.keevo.shared.infrastructure.persistence.TenantContext.clear();
    }

    @Mock private EndOfDayReportRepository reportRepository;
    @Mock private WhatsAppSender whatsAppSender;
    @Mock private MultiStoreSummaryService multiStoreSummaryService;
    @Mock private EndOfDayReportBuilder builder;
    @Mock private DailyReportFormatter formatter;
    @Mock private StoreRepository storeRepository;
    @Mock private OwnerRecipientResolver ownerRecipientResolver;
    @Mock private EmployeeRepository employeeRepository;

    private DailyReportGenerator generator;
    private UUID storeId;
    private UUID actorId;
    private GenerateReportCommand command;

    @BeforeEach
    void setUp() {
        generator = new DailyReportGenerator(
                reportRepository, whatsAppSender, multiStoreSummaryService,
                builder, formatter, StoreApiTestSupport.queryPort(storeRepository), ownerRecipientResolver, IdentityEmployeeApiTestSupport.queryPort(employeeRepository));
        storeId = UUID.randomUUID();
        actorId = UUID.randomUUID();
        command = new GenerateReportCommand(storeId, null, "kv_test01", false, Instant.now(), null);
    }

    private EndOfDayReportData sampleData() {
        return new EndOfDayReportData(
                "Boutique Test", LocalDate.now(), LocalTime.of(20, 0), false,
                false,
                5, 100000, 70000, 30000, 20000,
                List.of(), List.of(), 1, 0, 0, null
        );
    }

    @Test
    void generateReport_shouldReturnDailyType() {
        when(storeRepository.findById(storeId)).thenReturn(Optional.of(
                new Store(storeId, "Boutique Test", StoreType.STORE, null, null, true, Instant.now(), Instant.now())));
        when(builder.build(any(), any(ClosureWindow.class), anyString(), any(), any(), anyBoolean())).thenReturn(sampleData());
        when(formatter.format(any())).thenReturn("Rapport WhatsApp");
        when(ownerRecipientResolver.resolveOwnerWhatsAppPhone("kv_test01")).thenReturn(Optional.empty());

        EndOfDayReport report = generator.generateReport(command);
        assertThat(report.getReportType()).isEqualTo(ReportType.DAILY);
    }

    /**
     * J5 — un rapport personnel porte l'id UTILISATEUR du vendeur
     * (= sales.employee_id) : le nom doit être trouvé par user id, sinon
     * actor_name reste NULL et l'app affiche « Employé ».
     */
    @Test
    void employeeReport_actorNameResolvedFromTheUserId() {
        UUID userId = UUID.randomUUID();
        var empCommand = new GenerateReportCommand(storeId, userId, "kv_test01", false, Instant.now(), null);
        when(storeRepository.findById(storeId)).thenReturn(Optional.empty());
        when(employeeRepository.findByUserId(userId)).thenReturn(Optional.of(
                new com.keevo.identity.employee.domain.model.Employee(UUID.randomUUID(), userId, storeId,
                        "Jean", "Kamga",
                        com.keevo.identity.employee.domain.model.EmployeeStatus.ACTIVE,
                        false, Instant.now())));
        when(builder.buildForEmployee(any(), any(), any(ClosureWindow.class), anyString(), any(), any(),
                anyBoolean(), any())).thenReturn(employeeData("Jean Kamga"));
        when(formatter.formatEmployee(any())).thenReturn("Rapport");

        EndOfDayReport report = generator.generateReport(empCommand);

        verify(builder).buildForEmployee(eq(storeId), eq(userId), any(ClosureWindow.class), anyString(),
                any(), any(), eq(false), eq("Jean Kamga"));
        assertThat(report.getActorName()).isEqualTo("Jean Kamga");
        assertThat(report.getActorId()).isEqualTo(userId);
    }

    @Test
    void employeeReport_legacyEmployeeRecordId_stillResolvesTheName() {
        UUID employeeId = UUID.randomUUID();
        var empCommand = new GenerateReportCommand(storeId, employeeId, "kv_test01", false, Instant.now(), null);
        when(storeRepository.findById(storeId)).thenReturn(Optional.empty());
        when(employeeRepository.findByUserId(employeeId)).thenReturn(Optional.empty());
        when(employeeRepository.findById(employeeId)).thenReturn(Optional.of(
                new com.keevo.identity.employee.domain.model.Employee(employeeId, UUID.randomUUID(), storeId,
                        "Awa", "Ngo",
                        com.keevo.identity.employee.domain.model.EmployeeStatus.ACTIVE,
                        false, Instant.now())));
        when(builder.buildForEmployee(any(), any(), any(ClosureWindow.class), anyString(), any(), any(),
                anyBoolean(), any())).thenReturn(employeeData("Awa Ngo"));
        when(formatter.formatEmployee(any())).thenReturn("Rapport");

        generator.generateReport(empCommand);

        verify(builder).buildForEmployee(any(), any(), any(ClosureWindow.class), anyString(),
                any(), any(), anyBoolean(), eq("Awa Ngo"));
    }

    private EndOfDayReportData employeeData(String name) {
        return new EndOfDayReportData(
                "Boutique Test", LocalDate.now(), LocalTime.of(20, 0), false,
                true,
                2, 10000, 10000, 0, 5000,
                List.of(), List.of(), 0, 0, 0, name
        );
    }

    @Test
    void generateReport_whenStoreNotFound_shouldUseFallbackName() {
        when(storeRepository.findById(storeId)).thenReturn(Optional.empty());
        when(builder.build(any(), any(ClosureWindow.class), anyString(), any(), any(), anyBoolean())).thenReturn(sampleData());
        when(formatter.format(any())).thenReturn("Rapport");
        when(ownerRecipientResolver.resolveOwnerWhatsAppPhone(anyString())).thenReturn(Optional.empty());

        // Should not throw
        generator.generateReport(command);
        verify(builder, times(1)).build(eq(storeId), any(ClosureWindow.class), anyString(), any(), any(), eq(false));
    }

    @Test
    void generateReport_shouldUseOwnerPhoneFromResolver() {
        when(storeRepository.findById(storeId)).thenReturn(Optional.empty());
        when(builder.build(any(), any(ClosureWindow.class), anyString(), any(), any(), anyBoolean())).thenReturn(sampleData());
        when(formatter.format(any())).thenReturn("Rapport");
        when(ownerRecipientResolver.resolveOwnerWhatsAppPhone("kv_test01")).thenReturn(Optional.of("+243812345678"));

        generator.generateReport(command);
        verify(whatsAppSender, times(1)).sendReport(eq("+243812345678"), anyString());
    }

    @Test
    void generateReport_whenOwnerPhoneAbsent_shouldMarkInAppOnly() {
        when(storeRepository.findById(storeId)).thenReturn(Optional.empty());
        when(builder.build(any(), any(ClosureWindow.class), anyString(), any(), any(), anyBoolean())).thenReturn(sampleData());
        when(formatter.format(any())).thenReturn("Rapport");
        when(ownerRecipientResolver.resolveOwnerWhatsAppPhone(anyString())).thenReturn(Optional.empty());

        EndOfDayReport report = generator.generateReport(command);
        assertThat(report.getDeliveryStatus()).isEqualTo(DeliveryStatus.IN_APP_ONLY);
        verifyNoInteractions(whatsAppSender);
    }

    @Test
    void generateReport_shouldPersistWithTenantId() {
        when(storeRepository.findById(storeId)).thenReturn(Optional.empty());
        when(builder.build(any(), any(ClosureWindow.class), anyString(), any(), any(), anyBoolean())).thenReturn(sampleData());
        when(formatter.format(any())).thenReturn("Rapport");
        when(ownerRecipientResolver.resolveOwnerWhatsAppPhone(anyString())).thenReturn(Optional.empty());

        EndOfDayReport report = generator.generateReport(command);
        assertThat(report.getTenantId()).isEqualTo("kv_test01");
    }

    @Test
    void generateReport_deliverySuccess_shouldMarkSent() {
        when(storeRepository.findById(storeId)).thenReturn(Optional.empty());
        when(builder.build(any(), any(ClosureWindow.class), anyString(), any(), any(), anyBoolean())).thenReturn(sampleData());
        when(formatter.format(any())).thenReturn("Rapport");
        when(ownerRecipientResolver.resolveOwnerWhatsAppPhone(anyString())).thenReturn(Optional.of("+243812345678"));

        EndOfDayReport report = generator.generateReport(command);
        assertThat(report.getDeliveryStatus()).isEqualTo(DeliveryStatus.SENT);
    }

    @Test
    void generateReport_deliveryFailure_shouldMarkFailed() {
        when(storeRepository.findById(storeId)).thenReturn(Optional.empty());
        when(builder.build(any(), any(ClosureWindow.class), anyString(), any(), any(), anyBoolean())).thenReturn(sampleData());
        when(formatter.format(any())).thenReturn("Rapport");
        when(ownerRecipientResolver.resolveOwnerWhatsAppPhone(anyString())).thenReturn(Optional.of("+243812345678"));
        doThrow(new RuntimeException("net error")).when(whatsAppSender).sendReport(anyString(), anyString());

        EndOfDayReport report = generator.generateReport(command);
        assertThat(report.getDeliveryStatus()).isEqualTo(DeliveryStatus.FAILED);
    }

    /** v1s-14-17 — real resolver over a blank owner phone: IN_APP_ONLY, WhatsApp never called. */
    @Test
    void generateReport_withRealResolver_blankOwnerPhone_marksInAppOnly_neverSends() {
        UserRepository userRepository = mock(UserRepository.class);
        when(userRepository.findOwnerByTenantSchemaName("kv_test01")).thenReturn(Optional.of(
                new User(UUID.randomUUID(), "   ", "hash", Role.OWNER, true, Instant.now())));
        DailyReportGenerator realResolverGenerator = new DailyReportGenerator(
                reportRepository, whatsAppSender, multiStoreSummaryService,
                builder, formatter, StoreApiTestSupport.queryPort(storeRepository),
                new OwnerRecipientResolverAdapter(userRepository), IdentityEmployeeApiTestSupport.queryPort(employeeRepository));
        when(storeRepository.findById(storeId)).thenReturn(Optional.empty());
        when(builder.build(any(), any(ClosureWindow.class), anyString(), any(), any(), anyBoolean())).thenReturn(sampleData());
        when(formatter.format(any())).thenReturn("Rapport");

        EndOfDayReport report = realResolverGenerator.generateReport(command);

        assertThat(report.getDeliveryStatus()).isEqualTo(DeliveryStatus.IN_APP_ONLY);
        verify(whatsAppSender, never()).sendReport(anyString(), anyString());
    }
}
