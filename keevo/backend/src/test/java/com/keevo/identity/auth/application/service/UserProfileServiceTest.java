package com.keevo.identity.auth.application.service;

import com.keevo.store.store.adapter.in.api.StoreApiTestSupport;
import com.keevo.identity.api.Role;
import com.keevo.identity.auth.domain.model.User;
import com.keevo.identity.auth.domain.model.UserProfileData;
import com.keevo.identity.auth.domain.port.out.UserRepository;
import com.keevo.identity.employee.domain.model.Employee;
import com.keevo.identity.employee.domain.model.EmployeeStatus;
import com.keevo.identity.employee.domain.port.out.EmployeeRepository;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.store.store.domain.model.Store;
import com.keevo.store.api.StoreType;
import com.keevo.store.store.domain.port.out.StoreRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

/**
 * UserProfileServiceTest — Unit tests for Story 8.6 AC4.
 *
 * <p>3 scenarios:
 *   1. EMPLOYEE with store → full profile
 *   2. OWNER → partial profile (firstName, lastName, storeId, storeName = null)
 *   3. EMPLOYEE with store not found → storeName = null (graceful)
 */
@ExtendWith(MockitoExtension.class)
class UserProfileServiceTest {

    // 12.2d: the store / identity / subscription API adapters require an explicit, current tenant schema
    @org.junit.jupiter.api.BeforeEach
    void bindTenantContext() {
        com.keevo.shared.infrastructure.persistence.TenantContext.setCurrentTenant("kv_test01");
    }

    @org.junit.jupiter.api.AfterEach
    void clearTenantContext12d() {
        com.keevo.shared.infrastructure.persistence.TenantContext.clear();
    }

    @Mock private UserRepository userRepository;
    @Mock private EmployeeRepository employeeRepository;
    @Mock private StoreRepository storeRepository;
    @Mock private com.keevo.identity.auth.domain.port.out.TenantRepository tenantRepository;
    @Mock private com.keevo.identity.auth.domain.port.out.UserMembershipRepository membershipRepository;
    @Mock private SessionTokenIssuer sessionTokenIssuer;

    private UserProfileService sut;

    private UUID userId;
    private UUID storeId;
    private String tenantId;
    private User user;

    @BeforeEach
    void setUp() {
        // 12.2d: the real store API adapter over the mocked repository
        sut = new UserProfileService(userRepository, employeeRepository, StoreApiTestSupport.queryPort(storeRepository),
                tenantRepository, membershipRepository, sessionTokenIssuer);
        userId   = UUID.randomUUID();
        storeId  = UUID.randomUUID();
        tenantId = "kv_test123";
        user = new User(userId, "+237600000001", "hashedPwd", Role.EMPLOYEE, true, Instant.now());
    }

    @Test
    @DisplayName("EMPLOYEE with store → returns full profile including storeName")
    void employeeWithStore_returnsFullProfile() {
        Employee employee = new Employee(
                UUID.randomUUID(), userId, storeId,
                "Jean", "Dupont", EmployeeStatus.ACTIVE, false, Instant.now());
        Store store = new Store(storeId, "Boutique Centrale", StoreType.STORE,
                null, null, true, Instant.now(), Instant.now());

        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(employeeRepository.findByUserId(userId)).thenReturn(Optional.of(employee));
        when(storeRepository.findById(storeId)).thenReturn(Optional.of(store));

        UserProfileData result = sut.execute(userId, tenantId);

        assertThat(result.userId()).isEqualTo(userId);
        assertThat(result.phoneNumber()).isEqualTo("+237600000001");
        assertThat(result.role()).isEqualTo("EMPLOYEE");
        assertThat(result.firstName()).isEqualTo("Jean");
        assertThat(result.lastName()).isEqualTo("Dupont");
        assertThat(result.storeId()).isEqualTo(storeId.toString());
        assertThat(result.storeName()).isEqualTo("Boutique Centrale");
    }

    @Test
    @DisplayName("19.1 — phone-less (email-only) OWNER: phoneNumber is \"\" (never null for V1), names from public.users")
    void phonelessOwner_emptyPhone_namesFromUser() {
        User emailOnly = new User(userId, null, null, Role.OWNER, true, Instant.now(), 0, null,
                "awa@example.cm", null, "Awa", "Ndiaye", Instant.now(), null);

        when(userRepository.findById(userId)).thenReturn(Optional.of(emailOnly));
        when(employeeRepository.findByUserId(userId)).thenReturn(Optional.empty());

        UserProfileData result = sut.execute(userId, tenantId);

        assertThat(result.phoneNumber()).isEqualTo("");
        assertThat(result.role()).isEqualTo("OWNER");
        assertThat(result.firstName()).isEqualTo("Awa");
        assertThat(result.lastName()).isEqualTo("Ndiaye");
        assertThat(result.storeId()).isNull();
    }

    @Test
    @DisplayName("19.4 — username of a member is in the profile; a social technical username is never shown")
    void username_shown_technicalHidden() {
        User member = new User(userId, null, "hash", Role.EMPLOYEE, true, Instant.now(), 0, null,
                null, "Awa.K", null, null, null, null);
        when(userRepository.findById(userId)).thenReturn(Optional.of(member));
        when(employeeRepository.findByUserId(userId)).thenReturn(Optional.empty());
        assertThat(sut.execute(userId, tenantId).username()).isEqualTo("Awa.K");

        User social = new User(userId, null, null, Role.OWNER, true, Instant.now(), 0, null,
                null, "apple-0123abcd", null, null, null, null);
        when(userRepository.findById(userId)).thenReturn(Optional.of(social));
        assertThat(sut.execute(userId, tenantId).username()).isNull();
    }

    @Test
    @DisplayName("19.1 — phone-less EMPLOYEE: phoneNumber is \"\", names still from the employee record")
    void phonelessEmployee_emptyPhone() {
        User usernameOnly = new User(userId, null, "hash", Role.EMPLOYEE, true, Instant.now(), 0, null,
                null, "awa.n", "Ignored", "Ignored", null, null);
        Employee employee = new Employee(
                UUID.randomUUID(), userId, storeId,
                "Jean", "Dupont", EmployeeStatus.ACTIVE, false, Instant.now());

        when(userRepository.findById(userId)).thenReturn(Optional.of(usernameOnly));
        when(employeeRepository.findByUserId(userId)).thenReturn(Optional.of(employee));
        when(storeRepository.findById(storeId)).thenReturn(Optional.empty());

        UserProfileData result = sut.execute(userId, tenantId);

        assertThat(result.phoneNumber()).isEqualTo("");
        assertThat(result.firstName()).isEqualTo("Jean");
        assertThat(result.lastName()).isEqualTo("Dupont");
    }

    @Test
    @DisplayName("OWNER (no employee record) → returns profile with null fields")
    void owner_returnsPartialProfile() {
        User ownerUser = new User(userId, "+237600000002", "hashedPwd", Role.OWNER, true, Instant.now());

        when(userRepository.findById(userId)).thenReturn(Optional.of(ownerUser));
        when(employeeRepository.findByUserId(userId)).thenReturn(Optional.empty());

        UserProfileData result = sut.execute(userId, tenantId);

        assertThat(result.userId()).isEqualTo(userId);
        assertThat(result.phoneNumber()).isEqualTo("+237600000002");
        assertThat(result.role()).isEqualTo("OWNER");
        assertThat(result.firstName()).isNull();
        assertThat(result.lastName()).isNull();
        assertThat(result.storeId()).isNull();
        assertThat(result.storeName()).isNull();
    }

    @Test
    @DisplayName("EMPLOYEE with store not found → returns storeId but storeName = null")
    void employeeStoreNotFound_returnsNullStoreName() {
        Employee employee = new Employee(
                UUID.randomUUID(), userId, storeId,
                "Marie", "Curie", EmployeeStatus.ACTIVE, false, Instant.now());

        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(employeeRepository.findByUserId(userId)).thenReturn(Optional.of(employee));
        when(storeRepository.findById(storeId)).thenReturn(Optional.empty());

        UserProfileData result = sut.execute(userId, tenantId);

        assertThat(result.role()).isEqualTo("EMPLOYEE");
        assertThat(result.storeId()).isEqualTo(storeId.toString());
        assertThat(result.storeName()).isNull();
    }

    @Test
    @DisplayName("16.11 — role, account status, password flag and onboarding come from the database")
    void profileCarriesServerSideIdentity() {
        var tenant = new com.keevo.identity.auth.domain.model.Tenant(UUID.randomUUID(), "KV-TEST12", tenantId,
                com.keevo.identity.api.TenantStatus.SUSPENDED, com.keevo.identity.api.PlanType.FREE, Instant.now());
        Employee employee = new Employee(UUID.randomUUID(), userId, storeId,
                "Awa", "Ngo", EmployeeStatus.ACTIVE, true, Instant.now());
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(tenantRepository.findBySchemaName(tenantId)).thenReturn(Optional.of(tenant));
        when(membershipRepository.findByUserIdAndTenantId(userId, tenant.getId())).thenReturn(Optional.of(
                com.keevo.identity.auth.domain.model.UserTenantMembership.create(userId, tenant.getId(), "EMPLOYEE")));
        when(sessionTokenIssuer.isOnboardingCompleted(tenantId)).thenReturn(true);
        when(employeeRepository.findByUserId(userId)).thenReturn(Optional.of(employee));
        when(storeRepository.findById(storeId)).thenReturn(Optional.empty());

        UserProfileData result = sut.execute(userId, tenantId);

        assertThat(result.role()).isEqualTo("EMPLOYEE");
        assertThat(result.passwordChangeRequired()).isTrue();
        assertThat(result.tenantStatus()).isEqualTo("SUSPENDED");
        assertThat(result.onboardingCompleted()).isTrue();
    }

    @Test
    @DisplayName("16.11 — employee promoted to OWNER: role from the membership, no store scope")
    void promotedOwnerKeepsNamesWithoutStore() {
        var tenant = new com.keevo.identity.auth.domain.model.Tenant(UUID.randomUUID(), "KV-TEST12", tenantId,
                com.keevo.identity.api.TenantStatus.ACTIVE, com.keevo.identity.api.PlanType.FREE, Instant.now());
        Employee employee = new Employee(UUID.randomUUID(), userId, storeId,
                "Awa", "Ngo", EmployeeStatus.ACTIVE, false, Instant.now());
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(tenantRepository.findBySchemaName(tenantId)).thenReturn(Optional.of(tenant));
        when(membershipRepository.findByUserIdAndTenantId(userId, tenant.getId())).thenReturn(Optional.of(
                com.keevo.identity.auth.domain.model.UserTenantMembership.create(userId, tenant.getId(), "OWNER")));
        when(employeeRepository.findByUserId(userId)).thenReturn(Optional.of(employee));

        UserProfileData result = sut.execute(userId, tenantId);

        assertThat(result.role()).isEqualTo("OWNER");
        assertThat(result.firstName()).isEqualTo("Awa");
        assertThat(result.storeId()).isNull();
    }

    @Test
    @DisplayName("User not found → throws DomainException INVALID_CREDENTIALS")
    void userNotFound_throwsDomainException() {
        when(userRepository.findById(userId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> sut.execute(userId, tenantId))
                .isInstanceOf(DomainException.class);
    }
}
