package com.keevo.shared.domain.port.out;

import java.util.UUID;

/**
 * Story 16.6 (RGPD, reprise 2026-11-10) — deletes the personal data a module keeps for one member of one company
 * (the assistant's saved conversations…) when that member is removed from the company. Called by the member removal
 * ({@code RemoveEmployeeService}) inside its transaction, after the membership is deleted: an implementation throws on
 * failure so the removal is rolled back and can be retried. A company where the module never stored anything (module
 * table absent) is a no-op. The account deletion does not call it — the tenant schema is dropped as a whole.
 */
public interface MemberDataPurger {

    /** Delete every row of {@code userId} the module keeps in {@code tenantSchema} (validated schema name). */
    void purgeMember(String tenantSchema, UUID userId);
}
