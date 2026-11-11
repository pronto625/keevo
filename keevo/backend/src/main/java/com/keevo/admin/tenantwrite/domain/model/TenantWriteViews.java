package com.keevo.admin.tenantwrite.domain.model;

import com.keevo.admin.crossaccount.domain.model.CrossAccountViews.CustomerDetail;

/**
 * Story 20.13 (decisions A1–A4, 2026-11-08) — inputs and results of the super-admin writes in one account: customers
 * (crm rules) and members (identity rules). Every write goes through the owning module's api port.
 */
public final class TenantWriteViews {

    private TenantWriteViews() {
    }

    /** A customer's fields (create: name + phone required; update: null = kept). {@code birthDate} {@code yyyy-MM-dd}. */
    public record CustomerInput(String name, String phone, String email, String notes, String storeId,
                                String clientType, String niu, String rccm, String address, String contactName,
                                String website, String birthDate) {
    }

    /** The created customer and the number of other customers of its store with the same phone (warning only). */
    public record CustomerCreated(CustomerDetail customer, int duplicatePhoneCount) {
    }

    /**
     * A member's fields. Create: phone (E.164), first name, store required; role MANAGER / EMPLOYEE (default) / VIEWER.
     * Update: null = kept; role MANAGER / EMPLOYEE / VIEWER / OWNER.
     */
    public record MemberInput(String phoneNumber, String firstName, String lastName, String storeId, String role) {
    }

    /**
     * A created member; {@code temporaryPassword} shown once. Story 19.6b — {@code invitationPending}: the phone belongs
     * to an existing account, which was invited (its acceptance makes it a member): ids, password and member null.
     */
    public record MemberCreated(String employeeId, String userId, String temporaryPassword, boolean existingAccount,
                                MemberView member, boolean invitationPending) {
    }

    /** A member of the account. */
    public record MemberView(String employeeId, String userId, String storeId, String firstName, String lastName,
                             boolean active, String statusLabel) {
    }
}
