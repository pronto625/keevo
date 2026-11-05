package com.keevo.identity.employee.application.service;

import com.keevo.identity.employee.domain.model.Employee;
import com.keevo.identity.employee.domain.model.EmployeeStatus;
import com.keevo.identity.employee.domain.port.in.ListEmployeesQuery;
import com.keevo.identity.employee.domain.port.out.EmployeeRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("ListEmployeesService — tenant-wide or one store (Story 19.5)")
class ListEmployeesServiceTest {

    @Mock EmployeeRepository employeeRepository;

    private static Employee employee(UUID store) {
        return new Employee(UUID.randomUUID(), UUID.randomUUID(), store, "Awa", "Kamga", EmployeeStatus.ACTIVE, false,
                Instant.now());
    }

    @Test
    @DisplayName("no store → every employee (tenant-wide query only)")
    void nullStore_all() {
        List<Employee> all = List.of(employee(UUID.randomUUID()), employee(UUID.randomUUID()));
        when(employeeRepository.findAllOrderByStatusAndCreatedAt()).thenReturn(all);

        assertThat(new ListEmployeesService(employeeRepository).execute(new ListEmployeesQuery(UUID.randomUUID())))
                .isEqualTo(all);
        verify(employeeRepository, never()).findAllByStoreOrderByStatusAndCreatedAt(org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("a store → that store's query only")
    void store_storeQueryOnly() {
        UUID store = UUID.randomUUID();
        List<Employee> team = List.of(employee(store));
        when(employeeRepository.findAllByStoreOrderByStatusAndCreatedAt(store)).thenReturn(team);

        assertThat(new ListEmployeesService(employeeRepository).execute(new ListEmployeesQuery(UUID.randomUUID(), store)))
                .isEqualTo(team);
        verify(employeeRepository, never()).findAllOrderByStatusAndCreatedAt();
    }
}
