package com.keevo.admin.tenantwrite.application.service;

import com.keevo.admin.crossaccount.domain.port.out.CrossAccountReadPort;
import com.keevo.admin.crosstenant.CrossTenantSql;
import com.keevo.admin.crosstenant.TenantBranch;
import com.keevo.admin.crosstenant.TenantScope;
import com.keevo.admin.tenantwrite.domain.model.TenantWriteViews.CustomerInput;
import com.keevo.admin.tenantwrite.domain.model.TenantWriteViews.MemberInput;
import com.keevo.crm.api.ClientCommandPort;
import com.keevo.identity.api.EmployeeCommandPort;
import com.keevo.identity.api.EmployeeQueryPort;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("20.13 — super-admin account writes (decisions A1–A4): validation and delegation")
class TenantWriteServiceTest {

    private static final String TENANT = "6f1c2a52-3c1e-4b8e-9a77-0d4f2c9b1e11";
    private static final String EMPLOYEE = "0b6e3f1c-8a8d-4c51-9a0e-1d2f3a4b5c6d";
    private final UUID actor = UUID.randomUUID();
    private final TenantBranch tenant = new TenantBranch(TENANT, "Alpha", "kv_aaaaaa", "ACTIVE", null);

    @Mock CrossTenantSql crossTenant;
    @Mock TenantScope scope;
    @Mock ClientCommandPort clients;
    @Mock CrossAccountReadPort reader;
    @Mock EmployeeCommandPort members;
    @Mock EmployeeQueryPort memberQueries;

    TenantWriteService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        service = new TenantWriteService(crossTenant, scope, clients, reader, members, memberQueries);
        lenient().when(crossTenant.requireTenant(anyString())).thenReturn(tenant);
        lenient().when(scope.call(any(), any())).thenAnswer(inv -> ((Supplier<Object>) inv.getArgument(1)).get());
        lenient().doAnswer(inv -> {
            ((Runnable) inv.getArgument(1)).run();
            return null;
        }).when(scope).run(any(), any());
    }

    @Test
    @DisplayName("customer: name and phone required, empty update, store change and missing consent refused (422)")
    void customerValidation() {
        assertCode(() -> service.createCustomer(actor, TENANT, customer(null, "+237690000001")));
        assertCode(() -> service.createCustomer(actor, TENANT, customer("Awa", " ")));
        assertCode(() -> service.updateCustomer(actor, TENANT, EMPLOYEE, null));
        assertCode(() -> service.updateCustomer(actor, TENANT, EMPLOYEE,
                new CustomerInput(null, null, null, null, TENANT, null, null, null, null, null, null, null)));
        assertCode(() -> service.setMarketingConsent(actor, TENANT, EMPLOYEE, null));
        verifyNoInteractions(clients);
    }

    @Test
    @DisplayName("member: phone, first name, store required; unknown role refused (422); nothing written")
    void memberValidation() {
        assertCode(() -> service.createMember(actor, TENANT, new MemberInput(null, "Paul", null, TENANT, null)));
        assertCode(() -> service.createMember(actor, TENANT, new MemberInput("+237690000002", "", null, TENANT, null)));
        assertCode(() -> service.createMember(actor, TENANT, new MemberInput("+237690000002", "Paul", null, null, null)));
        assertCode(() -> service.createMember(actor, TENANT,
                new MemberInput("+237690000002", "Paul", null, TENANT, "OWNER")));
        assertCode(() -> service.updateMember(actor, TENANT, EMPLOYEE, new MemberInput(null, null, null, null, null)));
        assertCode(() -> service.updateMember(actor, TENANT, EMPLOYEE, new MemberInput(null, null, null, null, "BOSS")));
        verifyNoInteractions(members);
    }

    @Test
    @DisplayName("an unknown member → 404 « Employé introuvable », no command")
    void unknownMember() {
        when(memberQueries.findByUserId(eq("kv_aaaaaa"), any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.removeMember(actor, TENANT, EMPLOYEE)).isInstanceOf(DomainException.class)
                .satisfies(e -> assertThat(((DomainException) e).getUserMessage()).isEqualTo("Employé introuvable"))
                .extracting(e -> ((DomainException) e).getErrorCode()).isEqualTo(ErrorCode.EMPLOYEE_NOT_FOUND);
        verify(members, never()).remove(any(), any(), any());
    }

    private static CustomerInput customer(String name, String phone) {
        return new CustomerInput(name, phone, null, null, null, null, null, null, null, null, null, null);
    }

    private static void assertCode(Runnable call) {
        assertThatThrownBy(call::run).isInstanceOf(DomainException.class)
                .extracting(e -> ((DomainException) e).getErrorCode()).isEqualTo(ErrorCode.VALIDATION_ERROR);
    }
}
