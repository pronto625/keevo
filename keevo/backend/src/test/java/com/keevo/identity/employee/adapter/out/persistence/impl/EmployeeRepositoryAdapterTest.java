package com.keevo.identity.employee.adapter.out.persistence.impl;

import com.keevo.identity.employee.adapter.out.persistence.EmployeeJpaEntity;
import com.keevo.identity.employee.adapter.out.persistence.EmployeeSpringRepository;
import com.keevo.identity.employee.domain.model.Employee;
import com.keevo.identity.employee.domain.model.EmployeeStatus;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import com.keevo.shared.infrastructure.persistence.JpaBaseEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * EmployeeRepositoryAdapterTest — characterizes domain↔JPA mapping for EmployeeRepositoryAdapter.
 * Story 15.3 — Task 4.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("EmployeeRepositoryAdapter")
class EmployeeRepositoryAdapterTest {

    @Mock
    private EmployeeSpringRepository jpa;

    private EmployeeRepositoryAdapter adapter;

    private static final UUID EMPLOYEE_ID = UUID.randomUUID();
    private static final UUID USER_ID = UUID.randomUUID();
    private static final UUID STORE_ID = UUID.randomUUID();
    private static final Instant NOW = Instant.now();

    private Employee sampleEmployee;
    private EmployeeJpaEntity sampleEntity;

    @BeforeEach
    void setUp() {
        adapter = new EmployeeRepositoryAdapter(jpa);
        sampleEmployee = new Employee(EMPLOYEE_ID, USER_ID, STORE_ID,
                "Jean", "Dupont", EmployeeStatus.ACTIVE, true, NOW);
        sampleEntity = toEntity(sampleEmployee);
    }

    private EmployeeJpaEntity toEntity(Employee e) {
        EmployeeJpaEntity entity = new EmployeeJpaEntity();
        entity.assignId(e.getId());
        entity.setUserId(e.getUserId());
        entity.setStoreId(e.getStoreId());
        entity.setFirstName(e.getFirstName());
        entity.setLastName(e.getLastName());
        entity.setStatus(e.getStatus().name());
        entity.setPasswordChangeRequired(e.isPasswordChangeRequired());
        // JpaBaseEntity.createdAt is set by @PrePersist — set manually for tests
        setField(entity, "createdAt", NOW);
        setField(entity, "updatedAt", NOW);
        return entity;
    }

    private void setField(Object target, String fieldName, Object value) {
        try {
            Field f = JpaBaseEntity.class.getDeclaredField(fieldName);
            f.setAccessible(true);
            f.set(target, value);
        } catch (Exception ex) {
            throw new RuntimeException("Cannot set " + fieldName, ex);
        }
    }

    // ─────────────────────────────────────────────────────────────────
    // 4.2 — save
    // ─────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("save maps domain to entity and back")
    void save_mapsDomainToEntityAndBack() {
        when(jpa.save(any(EmployeeJpaEntity.class))).thenReturn(sampleEntity);

        Employee result = adapter.save(sampleEmployee);

        assertThat(result.getId()).isEqualTo(EMPLOYEE_ID);
        assertThat(result.getFirstName()).isEqualTo("Jean");
        assertThat(result.getLastName()).isEqualTo("Dupont");
        assertThat(result.getStatus()).isEqualTo(EmployeeStatus.ACTIVE);
        verify(jpa).save(any(EmployeeJpaEntity.class));
    }

    // ─────────────────────────────────────────────────────────────────
    // 4.3 — findById
    // ─────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("findById when found returns mapped employee")
    void findById_whenFound_returnsMappedEmployee() {
        when(jpa.findById(EMPLOYEE_ID)).thenReturn(Optional.of(sampleEntity));

        Optional<Employee> result = adapter.findById(EMPLOYEE_ID);

        assertTrue(result.isPresent());
        assertThat(result.get().getId()).isEqualTo(EMPLOYEE_ID);
        assertThat(result.get().getFirstName()).isEqualTo("Jean");
    }

    @Test
    @DisplayName("findById when not found returns empty")
    void findById_whenNotFound_returnsEmpty() {
        when(jpa.findById(EMPLOYEE_ID)).thenReturn(Optional.empty());

        Optional<Employee> result = adapter.findById(EMPLOYEE_ID);

        assertTrue(result.isEmpty());
    }

    // ─────────────────────────────────────────────────────────────────
    // 4.4 — findByUserId
    // ─────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("findByUserId returns mapped employee")
    void findByUserId_returnsMappedEmployee() {
        when(jpa.findByUserId(USER_ID)).thenReturn(Optional.of(sampleEntity));

        Optional<Employee> result = adapter.findByUserId(USER_ID);

        assertTrue(result.isPresent());
        assertThat(result.get().getUserId()).isEqualTo(USER_ID);
    }

    // ─────────────────────────────────────────────────────────────────
    // 4.5 — findAllOrderByStatusAndCreatedAt
    // ─────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("findAllOrderByStatusAndCreatedAt returns mapped list")
    void findAllOrderByStatusAndCreatedAt_returnsMappedList() {
        when(jpa.findAllByOrderByStatusAscCreatedAtAsc()).thenReturn(List.of(sampleEntity));

        List<Employee> result = adapter.findAllOrderByStatusAndCreatedAt();

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getFirstName()).isEqualTo("Jean");
    }

    // ─────────────────────────────────────────────────────────────────
    // 4.6 — updateStoreId
    // ─────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("updateStoreId updates and returns employee")
    void updateStoreId_updatesAndReturnsEmployee() {
        UUID newStoreId = UUID.randomUUID();
        when(jpa.findById(EMPLOYEE_ID)).thenReturn(Optional.of(sampleEntity));
        EmployeeJpaEntity updatedEntity = toEntity(sampleEmployee);
        updatedEntity.setStoreId(newStoreId);
        when(jpa.save(any(EmployeeJpaEntity.class))).thenReturn(updatedEntity);

        Employee result = adapter.updateStoreId(EMPLOYEE_ID, newStoreId);

        assertThat(result.getStoreId()).isEqualTo(newStoreId);
        verify(jpa).save(any(EmployeeJpaEntity.class));
    }

    @Test
    @DisplayName("updateStoreId when not found throws EMPLOYEE_NOT_FOUND")
    void updateStoreId_whenNotFound_throwsDomainException_EMPLOYEE_NOT_FOUND() {
        when(jpa.findById(EMPLOYEE_ID)).thenReturn(Optional.empty());

        DomainException ex = assertThrows(DomainException.class,
                () -> adapter.updateStoreId(EMPLOYEE_ID, UUID.randomUUID()));
        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.EMPLOYEE_NOT_FOUND);
    }

    // ─────────────────────────────────────────────────────────────────
    // 4.7 — updateStatus, updatePasswordChangeRequired, updateProfile
    // ─────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("updateStatus updates status field")
    void updateStatus_updatesStatusField() {
        when(jpa.findById(EMPLOYEE_ID)).thenReturn(Optional.of(sampleEntity));
        EmployeeJpaEntity inactiveEntity = toEntity(sampleEmployee);
        inactiveEntity.setStatus("INACTIVE");
        when(jpa.save(any(EmployeeJpaEntity.class))).thenReturn(inactiveEntity);

        Employee result = adapter.updateStatus(EMPLOYEE_ID, EmployeeStatus.INACTIVE);

        assertThat(result.getStatus()).isEqualTo(EmployeeStatus.INACTIVE);
    }

    @Test
    @DisplayName("updatePasswordChangeRequired updates field")
    void updatePasswordChangeRequired_updatesField() {
        when(jpa.findById(EMPLOYEE_ID)).thenReturn(Optional.of(sampleEntity));
        EmployeeJpaEntity updated = toEntity(sampleEmployee);
        updated.setPasswordChangeRequired(false);
        when(jpa.save(any(EmployeeJpaEntity.class))).thenReturn(updated);

        Employee result = adapter.updatePasswordChangeRequired(EMPLOYEE_ID, false);

        assertThat(result.isPasswordChangeRequired()).isFalse();
    }

    @Test
    @DisplayName("updateProfile updates first and last name")
    void updateProfile_updatesFirstAndLastName() {
        when(jpa.findById(EMPLOYEE_ID)).thenReturn(Optional.of(sampleEntity));
        EmployeeJpaEntity updated = toEntity(sampleEmployee);
        updated.setFirstName("Marie");
        updated.setLastName("Curie");
        when(jpa.save(any(EmployeeJpaEntity.class))).thenReturn(updated);

        Employee result = adapter.updateProfile(EMPLOYEE_ID, "Marie", "Curie");

        assertThat(result.getFirstName()).isEqualTo("Marie");
        assertThat(result.getLastName()).isEqualTo("Curie");
    }

    // ─────────────────────────────────────────────────────────────────
    // 4.8 — findByStoreId only returns ACTIVE employees
    // ─────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("findByStoreId only returns active employees (hidden behavior)")
    void findByStoreId_onlyReturnsActiveEmployees() {
        when(jpa.findByStoreIdAndStatus(STORE_ID, EmployeeStatus.ACTIVE.name())).thenReturn(List.of(sampleEntity));

        List<Employee> result = adapter.findByStoreId(STORE_ID);

        assertThat(result).hasSize(1);
        verify(jpa).findByStoreIdAndStatus(STORE_ID, "ACTIVE");
    }
}
