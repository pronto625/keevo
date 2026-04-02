package com.keevo.identity.onboarding.application.service;

import com.keevo.commerce.sale.domain.port.out.WhatsAppPort;
import com.keevo.identity.auth.domain.model.User;
import com.keevo.identity.auth.domain.port.out.UserRepository;
import com.keevo.identity.onboarding.domain.port.in.SendTestReportUseCase;
import com.keevo.reporting.report.application.service.DailyReportFormatter;
import com.keevo.reporting.report.application.service.EndOfDayReportBuilder;
import com.keevo.reporting.report.domain.model.EndOfDayReportData;
import com.keevo.store.store.domain.model.Store;
import com.keevo.store.store.domain.port.out.StoreRepository;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

/**
 * SendTestReportService — Generates a prefixed test WhatsApp report for the current tenant.
 *
 * <p>The report is generated on-the-fly for the first active store with an empty window.
 * It is NOT persisted. The prefix "🧪 [TEST]" clearly identifies it as a test.
 */
@Service
public class SendTestReportService implements SendTestReportUseCase {

    private static final String TEST_PREFIX = "🧪 [TEST] ";

    private final EndOfDayReportBuilder reportBuilder;
    private final DailyReportFormatter formatter;
    private final WhatsAppPort whatsAppPort;
    private final StoreRepository storeRepository;
    private final UserRepository userRepository;

    public SendTestReportService(EndOfDayReportBuilder reportBuilder,
                                 DailyReportFormatter formatter,
                                 WhatsAppPort whatsAppPort,
                                 StoreRepository storeRepository,
                                 UserRepository userRepository) {
        this.reportBuilder = reportBuilder;
        this.formatter = formatter;
        this.whatsAppPort = whatsAppPort;
        this.storeRepository = storeRepository;
        this.userRepository = userRepository;
    }

    @Override
    public TestReportResult sendTestReport(String tenantId) {
        if (!whatsAppPort.isConfigured()) {
            return new TestReportResult(false);
        }

        // Resolve owner phone using tenantId (schema name) — no TenantContext dependency
        String ownerPhone = userRepository.findOwnerByTenantSchemaName(tenantId)
                .map(User::getPhoneNumber)
                .orElse(null);

        if (ownerPhone == null || ownerPhone.isBlank()) {
            return new TestReportResult(false);
        }

        // Pick first active store (test report uses empty window)
        List<Store> stores = storeRepository.findAllActive();
        if (stores.isEmpty()) {
            return new TestReportResult(false);
        }

        Store store = stores.get(0);
        Instant now = Instant.now();

        try {
            EndOfDayReportData data = reportBuilder.build(
                store.id(), now, now,
                store.name(), LocalDate.now(), LocalTime.now(), false
            );
            String reportText = TEST_PREFIX + formatter.format(data);
            whatsAppPort.sendReport(ownerPhone, reportText);
            return new TestReportResult(true);
        } catch (Exception e) {
            return new TestReportResult(false);
        }
    }
}
