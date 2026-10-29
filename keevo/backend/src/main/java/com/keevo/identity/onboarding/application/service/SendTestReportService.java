package com.keevo.identity.onboarding.application.service;

import com.keevo.shared.capability.notification.WhatsAppSender;
import com.keevo.identity.api.DailyReportTextPort;
import com.keevo.identity.api.DailyReportTextRequest;
import com.keevo.identity.api.OwnerRecipientResolver;
import com.keevo.identity.onboarding.domain.port.in.SendTestReportUseCase;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.infrastructure.persistence.TenantContext;
import com.keevo.store.api.StoreView;
import com.keevo.store.api.StoreQueryPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    private static final Logger log = LoggerFactory.getLogger(SendTestReportService.class);
    private static final String TEST_PREFIX = "🧪 [TEST] ";

    private final DailyReportTextPort dailyReportText;
    private final WhatsAppSender whatsAppSender;
    private final StoreQueryPort storeQuery;
    private final OwnerRecipientResolver ownerRecipientResolver;

    public SendTestReportService(DailyReportTextPort dailyReportText,
                                 WhatsAppSender whatsAppSender,
                                 StoreQueryPort storeQuery,
                                 OwnerRecipientResolver ownerRecipientResolver) {
        this.dailyReportText = dailyReportText;
        this.whatsAppSender = whatsAppSender;
        this.storeQuery = storeQuery;
        this.ownerRecipientResolver = ownerRecipientResolver;
    }

    @Override
    public TestReportResult sendTestReport(String tenantId) {
        if (!whatsAppSender.isConfigured()) {
            // WhatsApp not configured (check KEEVO_WHATSAPP_PROVIDER, WASSENDER_API_URL, etc.)
            return new TestReportResult(false);
        }

        // Resolve owner phone using tenantId (schema name) — no TenantContext dependency
        String ownerPhone = ownerRecipientResolver.resolveOwnerWhatsAppPhone(tenantId)
                .orElse(null);

        if (ownerPhone == null || ownerPhone.isBlank()) {
            return new TestReportResult(false);
        }

        // Pick first active store (test report uses empty window)
        List<StoreView> stores = storeQuery.findAllActive(TenantContext.getCurrentTenant());
        if (stores.isEmpty()) {
            return new TestReportResult(false);
        }

        StoreView store = stores.get(0);
        Instant now = Instant.now();

        try {
            String reportText = TEST_PREFIX + dailyReportText.dailyReportText(
                tenantId,
                new DailyReportTextRequest(store.id(), store.name(), now, now,
                        LocalDate.now(), LocalTime.now(), false));
            whatsAppSender.sendReport(ownerPhone, reportText);
            return new TestReportResult(true);
        } catch (IllegalStateException | DomainException e) {
            // Tenant guard (tenantId ≠ bound tenant) or invalid report request — a programming error,
            // not a delivery failure: logged as such, result unchanged (not sent).
            log.error("[report-test] test report not built for tenant {}", tenantId, e);
            return new TestReportResult(false);
        } catch (Exception e) {
            log.warn("[report-test] WhatsApp delivery failed for owner {} — {}", ownerPhone, e.getMessage());
            return new TestReportResult(false);
        }
    }
}
