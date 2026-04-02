package com.keevo.identity.onboarding.domain.port.in;

/**
 * SendTestReportUseCase — Port-in for sending a test WhatsApp report.
 * Story 7.5 — Task 2.6
 *
 * <p>Pure Java — NO Spring/framework imports.
 */
public interface SendTestReportUseCase {

    record TestReportResult(boolean testSent) {}

    /**
     * Generate and send a test report for the given tenant.
     * The report is NOT persisted in the reports table.
     *
     * @param tenantId the tenant schema name
     * @return result indicating whether the WhatsApp send succeeded
     */
    TestReportResult sendTestReport(String tenantId);
}
