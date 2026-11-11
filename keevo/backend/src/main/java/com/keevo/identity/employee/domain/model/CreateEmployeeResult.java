package com.keevo.identity.employee.domain.model;

/**
 * CreateEmployeeResult — Returned by CreateEmployeeUseCase.
 *
 * <p>Contains both the created employee AND the cleartext temporary password
 * for one-time display to the owner. The password is NEVER persisted in cleartext.
 *
 * <p>Story 3.5 — AC1. Story 19.6b — {@code invitationPending}: the identifier belongs to an existing account, which was
 * invited (or not, the result is the same whatever the account's state): no employee, no temporary password,
 * {@code existingAccount} true.
 */
public record CreateEmployeeResult(Employee employee, String temporaryPassword, boolean existingAccount,
                                   boolean invitationPending) {

    /** A new account (or a regenerated password): the temporary password is shown once. */
    public CreateEmployeeResult(Employee employee, String temporaryPassword) {
        this(employee, temporaryPassword, false, false);
    }

    /** Story 19.6b — the neutral result of adding an existing account: an invitation is pending if it can be. */
    public static CreateEmployeeResult pendingInvitation() {
        return new CreateEmployeeResult(null, null, true, true);
    }
}
