package com.keevo.admin.tenantwrite.application.service;

import com.keevo.admin.crossaccount.domain.model.CrossAccountViews.CustomerDetail;
import com.keevo.admin.crossaccount.domain.port.out.CrossAccountReadPort;
import com.keevo.admin.crosstenant.AdminParams;
import com.keevo.admin.crosstenant.CrossTenantSql;
import com.keevo.admin.crosstenant.TenantBranch;
import com.keevo.admin.crosstenant.TenantScope;
import com.keevo.admin.tenantwrite.domain.model.TenantWriteViews.CustomerCreated;
import com.keevo.admin.tenantwrite.domain.model.TenantWriteViews.CustomerInput;
import com.keevo.admin.tenantwrite.domain.model.TenantWriteViews.MemberCreated;
import com.keevo.admin.tenantwrite.domain.model.TenantWriteViews.MemberInput;
import com.keevo.admin.tenantwrite.domain.model.TenantWriteViews.MemberView;
import com.keevo.admin.tenantwrite.domain.port.in.TenantWriteUseCase;
import com.keevo.crm.api.ClientCommandPort;
import com.keevo.crm.api.ClientProfileInput;
import com.keevo.crm.api.CreateClientInput;
import com.keevo.crm.api.CreatedClient;
import com.keevo.crm.api.UpdateClientInput;
import com.keevo.identity.api.CreateMemberInput;
import com.keevo.identity.api.CreatedMember;
import com.keevo.identity.api.EmployeeCommandPort;
import com.keevo.identity.api.EmployeeQueryPort;
import com.keevo.identity.api.EmployeeView;
import com.keevo.identity.api.UpdateMemberInput;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Story 20.13 (decisions A1–A4, 2026-11-08) — super-admin writes in one account. Validates the request (422
 * {@code VALIDATION_ERROR}, French message), resolves the account (404 « Compte introuvable »), then calls the owning
 * module's api port inside the account's tenant scope ({@link TenantScope}): crm for customers (store rule, E.164 phone,
 * duplicate warning, consent author, audit), identity for members (owner creation rules: E.164 phone, plan limits,
 * temporary password, existing account; role-change rules; deactivation revokes the member's sessions of this account;
 * removal never touches the owner). The actor of every audit event is the super-admin. No SQL on module tables here.
 */
@Service
public class TenantWriteService implements TenantWriteUseCase {

    static final List<String> CREATE_ROLES = List.of("MANAGER", "EMPLOYEE", "VIEWER");
    static final List<String> UPDATE_ROLES = List.of("OWNER", "MANAGER", "EMPLOYEE", "VIEWER");
    static final String EMPLOYEE_NOT_FOUND = "Employé introuvable";

    private final CrossTenantSql crossTenant;
    private final TenantScope scope;
    private final ClientCommandPort clients;
    private final CrossAccountReadPort reader;
    private final EmployeeCommandPort members;
    private final EmployeeQueryPort memberQueries;

    public TenantWriteService(CrossTenantSql crossTenant, TenantScope scope, ClientCommandPort clients,
                              CrossAccountReadPort reader, EmployeeCommandPort members,
                              EmployeeQueryPort memberQueries) {
        this.crossTenant = crossTenant;
        this.scope = scope;
        this.clients = clients;
        this.reader = reader;
        this.members = members;
        this.memberQueries = memberQueries;
    }

    // ── customers (A1) ───────────────────────────────────────────────────────

    @Override
    public CustomerCreated createCustomer(UUID actorId, String tenantId, CustomerInput in) {
        CustomerInput input = in == null ? new CustomerInput(null, null, null, null, null, null, null, null, null,
                null, null, null) : in;
        if (blank(input.name())) {
            throw AdminParams.invalid("Le nom du client est obligatoire");
        }
        if (blank(input.phone())) {
            throw AdminParams.invalid("Le téléphone du client est obligatoire");
        }
        UUID storeId = uuid(input.storeId(), "Boutique invalide");
        TenantBranch tenant = crossTenant.requireTenant(tenantId);
        CreatedClient created = scope.call(tenant, () -> clients.create(tenant.schemaName(), new CreateClientInput(
                input.name(), input.phone(), input.email(), input.notes(), profile(input), storeId, actorId)));
        return new CustomerCreated(detail(tenant, created.client().id()), created.duplicates());
    }

    @Override
    public CustomerDetail updateCustomer(UUID actorId, String tenantId, String customerId, CustomerInput in) {
        if (in == null || (in.name() == null && in.phone() == null && in.email() == null && in.notes() == null
                && in.clientType() == null && in.niu() == null && in.rccm() == null && in.address() == null
                && in.contactName() == null && in.website() == null && in.birthDate() == null)) {
            throw AdminParams.invalid("Aucune modification demandée");
        }
        if (in.storeId() != null) {
            throw AdminParams.invalid("La boutique d'un client se change depuis l'application du propriétaire");
        }
        UUID id = uuid(customerId, "Identifiant invalide");
        TenantBranch tenant = crossTenant.requireTenant(tenantId);
        scope.call(tenant, () -> clients.update(tenant.schemaName(), new UpdateClientInput(id, in.name(), in.phone(),
                in.email(), in.notes(), actorId, profile(in))));
        return detail(tenant, id);
    }

    @Override
    public CustomerDetail setMarketingConsent(UUID actorId, String tenantId, String customerId, Boolean consent) {
        if (consent == null) {
            throw AdminParams.invalid("Indiquez l'accord du client : consent vaut true ou false");
        }
        UUID id = uuid(customerId, "Identifiant invalide");
        TenantBranch tenant = crossTenant.requireTenant(tenantId);
        scope.call(tenant, () -> clients.setMarketingConsent(tenant.schemaName(), id, consent, actorId));
        return detail(tenant, id);
    }

    private CustomerDetail detail(TenantBranch tenant, UUID id) {
        return reader.customer(tenant, id.toString())
                .orElseThrow(() -> DomainException.userFacing(ErrorCode.CLIENT_NOT_FOUND, "Client introuvable"));
    }

    private static ClientProfileInput profile(CustomerInput in) {
        return new ClientProfileInput(in.clientType(), in.niu(), in.rccm(), in.address(), in.contactName(),
                in.website(), in.birthDate());
    }

    // ── members (A2–A4) ──────────────────────────────────────────────────────

    @Override
    public MemberCreated createMember(UUID actorId, String tenantId, MemberInput in) {
        if (in == null || blank(in.phoneNumber())) {
            throw AdminParams.invalid("Le numéro de téléphone est obligatoire");
        }
        if (blank(in.firstName())) {
            throw AdminParams.invalid("Le prénom est obligatoire");
        }
        UUID storeId = uuid(in.storeId(), "Boutique invalide");
        if (storeId == null) {
            throw AdminParams.invalid("La boutique est obligatoire");
        }
        String role = role(in.role(), CREATE_ROLES, "Rôle inconnu : choisissez MANAGER, EMPLOYEE ou VIEWER");
        TenantBranch tenant = crossTenant.requireTenant(tenantId);
        return scope.call(tenant, () -> {
            CreatedMember created = members.createMember(tenant.schemaName(), new CreateMemberInput(actorId,
                    in.phoneNumber().trim(), in.firstName().trim(), in.lastName() == null ? null : in.lastName().trim(),
                    storeId, role));
            return new MemberCreated(created.employeeId().toString(), created.userId().toString(),
                    created.temporaryPassword(), created.existingAccount(), view(tenant, created.employeeId()));
        });
    }

    @Override
    public MemberView updateMember(UUID actorId, String tenantId, String userId, MemberInput in) {
        if (in == null || (in.phoneNumber() == null && in.firstName() == null && in.lastName() == null
                && in.storeId() == null && in.role() == null)) {
            throw AdminParams.invalid("Aucune modification demandée");
        }
        UUID storeId = uuid(in.storeId(), "Boutique invalide");
        String role = role(in.role(), UPDATE_ROLES, "Rôle inconnu : choisissez OWNER, MANAGER, EMPLOYEE ou VIEWER");
        UUID user = user(userId);
        TenantBranch tenant = crossTenant.requireTenant(tenantId);
        return scope.call(tenant, () -> {
            UUID id = requireMember(tenant, user);
            members.updateMember(tenant.schemaName(), new UpdateMemberInput(actorId, id, in.firstName(),
                    in.lastName(), in.phoneNumber(), storeId, role));
            return view(tenant, id);
        });
    }

    @Override
    public MemberView setMemberActive(UUID actorId, String tenantId, String userId, boolean active) {
        UUID user = user(userId);
        TenantBranch tenant = crossTenant.requireTenant(tenantId);
        return scope.call(tenant, () -> {
            UUID id = requireMember(tenant, user);
            if (active) {
                members.reactivate(tenant.schemaName(), actorId, id);
            } else {
                members.deactivate(tenant.schemaName(), actorId, id);
            }
            return view(tenant, id);
        });
    }

    @Override
    public void removeMember(UUID actorId, String tenantId, String userId) {
        UUID user = user(userId);
        TenantBranch tenant = crossTenant.requireTenant(tenantId);
        scope.run(tenant, () -> {
            UUID id = requireMember(tenant, user);
            members.remove(tenant.schemaName(), actorId, id);
        });
    }

    /** The employee record of the user in the account (the user id comes from the users list), else 404. */
    private UUID requireMember(TenantBranch tenant, UUID userId) {
        return memberQueries.findByUserId(tenant.schemaName(), userId).map(EmployeeView::id)
                .orElseThrow(() -> DomainException.userFacing(ErrorCode.EMPLOYEE_NOT_FOUND, EMPLOYEE_NOT_FOUND));
    }

    private MemberView view(TenantBranch tenant, UUID employeeId) {
        EmployeeView e = memberQueries.findById(tenant.schemaName(), employeeId)
                .orElseThrow(() -> DomainException.userFacing(ErrorCode.EMPLOYEE_NOT_FOUND, EMPLOYEE_NOT_FOUND));
        return new MemberView(e.id().toString(), e.userId() == null ? null : e.userId().toString(),
                e.storeId() == null ? null : e.storeId().toString(), e.firstName(), e.lastName(), e.active(),
                e.active() ? "Actif" : "Désactivé");
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private static UUID user(String userId) {
        UUID id = uuid(userId, "Identifiant invalide");
        if (id == null) {
            throw DomainException.userFacing(ErrorCode.EMPLOYEE_NOT_FOUND, EMPLOYEE_NOT_FOUND);
        }
        return id;
    }

    private static String role(String raw, List<String> allowed, String message) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String role = raw.trim().toUpperCase(Locale.ROOT);
        if (!allowed.contains(role)) {
            throw AdminParams.invalid(message);
        }
        return role;
    }

    private static UUID uuid(String raw, String message) {
        String id = CrossTenantSql.requireUuid(raw, message);
        return id == null ? null : UUID.fromString(id);
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
