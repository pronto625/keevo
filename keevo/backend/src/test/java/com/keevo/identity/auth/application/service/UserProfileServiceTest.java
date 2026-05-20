package com.keevo.identity.auth.application.service;

import com.keevo.identity.auth.domain.model.Role;
import com.keevo.identity.auth.domain.model.User;
import com.keevo.identity.auth.domain.model.UserProfileData;
import com.keevo.identity.auth.domain.port.out.UserRepository;
import com.keevo.identity.employee.domain.model.Employee;
import com.keevo.identity.employee.domain.model.EmployeeStatus;
import com.keevo.identity.employee.domain.port.out.EmployeeRepository;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.store.store.domain.model.Store;
import com.keevo.store.store.domain.model.StoreType;
import com.keevo.store.store.domain.port.out.StoreRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
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

    @Mock private UserRepository userRepository;
    @Mock private EmployeeRepository employeeRepository;
    @Mock private StoreRepository storeRepository;

    @InjectMocks
    private UserProfileService sut;

    private UUID userId;
    private UUID storeId;
    private String tenantId;
    private User user;

    @BeforeEach
    void setUp() {
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
    @DisplayName("User not found → throws DomainException INVALID_CREDENTIALS")
    void userNotFound_throwsDomainException() {
        when(userRepository.findById(userId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> sut.execute(userId, tenantId))
                .isInstanceOf(DomainException.class);
    }
}
