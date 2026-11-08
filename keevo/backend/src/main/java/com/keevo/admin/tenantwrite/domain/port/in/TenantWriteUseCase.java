package com.keevo.admin.tenantwrite.domain.port.in;

import com.keevo.admin.crossaccount.domain.model.CrossAccountViews.CustomerDetail;
import com.keevo.admin.tenantwrite.domain.model.TenantWriteViews.CustomerCreated;
import com.keevo.admin.tenantwrite.domain.model.TenantWriteViews.CustomerInput;
import com.keevo.admin.tenantwrite.domain.model.TenantWriteViews.MemberCreated;
import com.keevo.admin.tenantwrite.domain.model.TenantWriteViews.MemberInput;
import com.keevo.admin.tenantwrite.domain.model.TenantWriteViews.MemberView;

import java.util.UUID;

/**
 * Story 20.13 (decisions A1–A4) — super-admin writes in one account, with the owning module's rules and audit (the
 * actor is the super-admin): customers (Bref {@code POST/PATCH /customers}, {@code /marketing-consent}) and members
 * (Bref {@code POST/PATCH /users}, {@code PATCH /users/:id/status}, {@code DELETE /users/:id}).
 */
public interface TenantWriteUseCase {

    CustomerCreated createCustomer(UUID actorId, String tenantId, CustomerInput input);

    CustomerDetail updateCustomer(UUID actorId, String tenantId, String customerId, CustomerInput input);

    CustomerDetail setMarketingConsent(UUID actorId, String tenantId, String customerId, Boolean consent);

    MemberCreated createMember(UUID actorId, String tenantId, MemberInput input);

    /** {@code userId}: the member's user (users list); its employee record in the account is resolved. */
    MemberView updateMember(UUID actorId, String tenantId, String userId, MemberInput input);

    MemberView setMemberActive(UUID actorId, String tenantId, String userId, boolean active);

    void removeMember(UUID actorId, String tenantId, String userId);
}
