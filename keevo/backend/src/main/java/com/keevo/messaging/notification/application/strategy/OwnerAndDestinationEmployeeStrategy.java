package com.keevo.messaging.notification.application.strategy;

import com.keevo.catalog.stock.domain.event.TransferCreatedEvent;
import com.keevo.identity.auth.domain.port.out.UserRepository;
import com.keevo.identity.employee.domain.model.Employee;
import com.keevo.identity.employee.domain.port.out.EmployeeRepository;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * OwnerAndDestinationEmployeeStrategy — resolves OWNER users of the tenant
 * plus all ACTIVE EMPLOYEE users assigned to the destination store.
 *
 * <p>Results are deduplicated to prevent double notifications when an OWNER
 * is also represented in the employee table (edge case).
 *
 * Story HF-2 AC2.
 */
@Component
public class OwnerAndDestinationEmployeeStrategy implements TransferNotificationRecipientStrategy {

    private final UserRepository userRepository;
    private final EmployeeRepository employeeRepository;

    public OwnerAndDestinationEmployeeStrategy(UserRepository userRepository,
                                               EmployeeRepository employeeRepository) {
        this.userRepository = userRepository;
        this.employeeRepository = employeeRepository;
    }

    @Override
    public List<UUID> resolveRecipients(TransferCreatedEvent event) {
        List<UUID> recipients = new ArrayList<>();

        // 1. All OWNER users of the tenant
        recipients.addAll(
                userRepository.findOwnersByTenantSchemaName(event.tenantId())
                        .stream()
                        .map(user -> user.getId())
                        .collect(Collectors.toList())
        );

        // 2. All ACTIVE EMPLOYEE users assigned to the destination store
        // (findByStoreId already filters by ACTIVE status via EmployeeRepositoryAdapter)
        List<UUID> destinationEmployeeUserIds = employeeRepository
                .findByStoreId(event.destinationStoreId())
                .stream()
                .map(Employee::getUserId)
                .collect(Collectors.toList());
        recipients.addAll(destinationEmployeeUserIds);

        // Deduplicate in case an OWNER user ID appears in either list
        return recipients.stream().distinct().collect(Collectors.toList());
    }
}
