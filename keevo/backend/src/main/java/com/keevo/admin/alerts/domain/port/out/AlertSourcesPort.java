package com.keevo.admin.alerts.domain.port.out;

import com.keevo.admin.alerts.domain.model.AlertSourceRows.DeletionPending;
import com.keevo.admin.alerts.domain.model.AlertSourceRows.ExpiredPlan;
import com.keevo.admin.alerts.domain.model.AlertSourceRows.HighFeedback;
import com.keevo.admin.alerts.domain.model.AlertSourceRows.ReportFailures;
import com.keevo.admin.alerts.domain.model.AlertSourceRows.SilentTenant;

import java.time.Instant;
import java.util.List;

/**
 * AlertSourcesPort — facts behind the alerts feed (Story 9-5). Cross-company reads skip a company on
 * its own SQL/schema error and rethrow connection / resource failures.
 */
public interface AlertSourcesPort {

    /** HIGH-priority feedback submitted since {@code since} and not handled yet. */
    List<HighFeedback> unhandledHighFeedbackSince(Instant since);

    /** Companies in DELETION_PENDING. */
    List<DeletionPending> deletionPending();

    /** Live companies whose ACTIVE subscription expired before {@code expiredBefore} but is still not FREE. */
    List<ExpiredPlan> expiredPlansNotFree(Instant expiredBefore);

    /** WhatsApp report delivery failures (status FAILED) since {@code since}, all companies. */
    ReportFailures reportFailuresSince(Instant since);

    /**
     * ACTIVE companies with at least one device whose latest push is before {@code pushedBefore},
     * excluding those with a device registered since then that has not pushed yet.
     */
    List<SilentTenant> silentTenants(Instant pushedBefore);
}
