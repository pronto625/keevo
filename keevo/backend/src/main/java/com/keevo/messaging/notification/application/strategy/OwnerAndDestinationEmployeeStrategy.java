package com.keevo.messaging.notification.application.strategy;

import com.keevo.shared.infrastructure.persistence.TenantContext;
import com.keevo.shared.event.catalog.transfer.TransferCreatedEvent;
import com.keevo.identity.api.UserQueryPort;
import com.keevo.identity.api.EmployeeView;
import com.keevo.identity.api.EmployeeQueryPort;
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

    private final UserQueryPort userQuery;
    private final EmployeeQueryPort employeeQuery;

    public OwnerAndDestinationEmployeeStrategy(UserQueryPort userQuery,
                                               EmployeeQueryPort employeeQuery) {
        this.userQuery = userQuery;
        this.employeeQuery = employeeQuery;
    }

    @Override
    public List<UUID> resolveRecipients(TransferCreatedEvent event) {
        List<UUID> recipients = new ArrayList<>();

        // 1. All OWNER users of the tenant
        recipients.addAll(
                userQuery.findOwnerUserIds(event.tenantId())
        );

        // 2. All ACTIVE EMPLOYEE users assigned to the destination store
        // (findActiveByStoreId keeps ACTIVE employees only)
        List<UUID> destinationEmployeeUserIds = employeeQuery.findActiveByStoreId(TenantContext.getCurrentTenant(), event.destinationStoreId())
                .stream()
                .map(EmployeeView::userId)
                .collect(Collectors.toList());
        recipients.addAll(destinationEmployeeUserIds);

        // Deduplicate in case an OWNER user ID appears in either list
        return recipients.stream().distinct().collect(Collectors.toList());
    }
}
