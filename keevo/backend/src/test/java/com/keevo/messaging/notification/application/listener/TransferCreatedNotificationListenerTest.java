package com.keevo.messaging.notification.application.listener;

import com.keevo.catalog.stock.domain.event.TransferCreatedEvent;
import com.keevo.identity.auth.domain.model.User;
import com.keevo.identity.auth.domain.port.out.UserRepository;
import com.keevo.identity.auth.domain.model.Role;
import com.keevo.identity.employee.domain.model.Employee;
import com.keevo.identity.employee.domain.model.EmployeeStatus;
import com.keevo.identity.employee.domain.port.out.EmployeeRepository;
import com.keevo.messaging.notification.application.strategy.OwnerAndDestinationEmployeeStrategy;
import com.keevo.messaging.notification.domain.model.NotificationPayload;
import com.keevo.messaging.notification.domain.port.out.NotificationPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * TransferCreatedNotificationListenerTest — TDD tests for HF-2 AC2, AC7.
 *
 * <p>Verifies:
 * <ul>
 *   <li>Recipients include tenant OWNER + destination store employees (AC2)</li>
 *   <li>Deep link is router-valid: /stock/transfers (AC7)</li>
 *   <li>Empty recipient list triggers no notification call</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("TransferCreatedNotificationListener")
class TransferCreatedNotificationListenerTest {

    @Mock private UserRepository userRepository;
    @Mock private EmployeeRepository employeeRepository;
    @Mock private NotificationPort notificationPort;

    private TransferCreatedNotificationListener listener;

    private static final UUID TRANSFER_ID      = UUID.randomUUID();
    private static final UUID SOURCE_STORE_ID  = UUID.randomUUID();
    private static final UUID DEST_STORE_ID    = UUID.randomUUID();
    private static final UUID PRODUCT_ID       = UUID.randomUUID();
    private static final UUID ACTOR_ID         = UUID.randomUUID();
    private static final UUID OWNER_USER_ID    = UUID.randomUUID();
    private static final UUID EMP_USER_ID_1    = UUID.randomUUID();
    private static final UUID EMP_USER_ID_2    = UUID.randomUUID();
    private static final String TENANT_ID      = "kv_test_hf2";

    @BeforeEach
    void setUp() {
        // Inject real OwnerAndDestinationEmployeeStrategy with mocked repos so all AC2
        // recipient-logic tests remain valid while the listener delegates to the strategy.
        listener = new TransferCreatedNotificationListener(
                new OwnerAndDestinationEmployeeStrategy(userRepository, employeeRepository),
                notificationPort);
    }

    private TransferCreatedEvent makeEvent() {
        return new TransferCreatedEvent(
                TRANSFER_ID, SOURCE_STORE_ID, DEST_STORE_ID,
                PRODUCT_ID, 10, ACTOR_ID, TENANT_ID, Instant.now());
    }

    private User owner() {
                return new User(OWNER_USER_ID, "+33600000001", "hash", Role.OWNER, true, Instant.now());
    }

    private Employee employee(UUID userId) {
        return new Employee(UUID.randomUUID(), userId, DEST_STORE_ID,
                "Alice", "D", EmployeeStatus.ACTIVE, false, Instant.now());
    }

    // ── AC2 tests ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("AC2 — notifies OWNER when no destination employees exist")
    void onTransferCreated_notifiesOwner_whenNoDestinationEmployees() {
        when(userRepository.findOwnersByTenantSchemaName(TENANT_ID))
                .thenReturn(List.of(owner()));
        when(employeeRepository.findByStoreId(DEST_STORE_ID)).thenReturn(List.of());

        listener.onTransferCreated(makeEvent());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<UUID>> recipientsCaptor = ArgumentCaptor.forClass(List.class);
        verify(notificationPort).notifyUsers(eq(TENANT_ID), recipientsCaptor.capture(), any());
        assertThat(recipientsCaptor.getValue()).containsExactly(OWNER_USER_ID);
    }

    @Test
    @DisplayName("AC2 — notifies OWNER + destination employees")
    void onTransferCreated_notifiesOwnerAndDestinationEmployees() {
        when(userRepository.findOwnersByTenantSchemaName(TENANT_ID))
                .thenReturn(List.of(owner()));
        when(employeeRepository.findByStoreId(DEST_STORE_ID))
                .thenReturn(List.of(employee(EMP_USER_ID_1), employee(EMP_USER_ID_2)));

        listener.onTransferCreated(makeEvent());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<UUID>> recipientsCaptor = ArgumentCaptor.forClass(List.class);
        verify(notificationPort).notifyUsers(eq(TENANT_ID), recipientsCaptor.capture(), any());

        List<UUID> recipients = recipientsCaptor.getValue();
        assertThat(recipients).contains(OWNER_USER_ID, EMP_USER_ID_1, EMP_USER_ID_2);
        assertThat(recipients).hasSize(3);
    }

    @Test
    @DisplayName("AC2 — recipients are deduplicated when owner ID appears in employee list")
    void onTransferCreated_deduplicatesRecipients() {
        // Edge: owner is also listed as employee (shouldn't happen but must be safe)
        when(userRepository.findOwnersByTenantSchemaName(TENANT_ID))
                .thenReturn(List.of(owner()));
        Employee ownerAlsoEmployee = new Employee(UUID.randomUUID(), OWNER_USER_ID, DEST_STORE_ID,
                "Alice", "D", EmployeeStatus.ACTIVE, false, Instant.now());
        when(employeeRepository.findByStoreId(DEST_STORE_ID))
                .thenReturn(List.of(ownerAlsoEmployee));

        listener.onTransferCreated(makeEvent());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<UUID>> captor = ArgumentCaptor.forClass(List.class);
        verify(notificationPort).notifyUsers(eq(TENANT_ID), captor.capture(), any());
        assertThat(captor.getValue()).containsExactly(OWNER_USER_ID); // deduplicated
    }

    @Test
    @DisplayName("AC2 — no notification when no owner and no employees found")
    void onTransferCreated_doesNotNotify_whenNoRecipientsResolved() {
        when(userRepository.findOwnersByTenantSchemaName(TENANT_ID)).thenReturn(List.of());
        when(employeeRepository.findByStoreId(DEST_STORE_ID)).thenReturn(List.of());

        listener.onTransferCreated(makeEvent());

        verifyNoInteractions(notificationPort);
    }

    // ── AC7 tests ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("AC7 — deep link is /stock/transfers (router-valid)")
    void onTransferCreated_deepLinkIsRouterValid() {
        when(userRepository.findOwnersByTenantSchemaName(TENANT_ID))
                .thenReturn(List.of(owner()));
        when(employeeRepository.findByStoreId(DEST_STORE_ID)).thenReturn(List.of());

        listener.onTransferCreated(makeEvent());

        ArgumentCaptor<NotificationPayload> payloadCaptor =
                ArgumentCaptor.forClass(NotificationPayload.class);
        verify(notificationPort).notifyUsers(any(), any(), payloadCaptor.capture());

        assertThat(payloadCaptor.getValue().deepLink()).isEqualTo("/stock/transfers");
    }

    @Test
    @DisplayName("AC7 — notification is best-effort (no exception propagation)")
    void onTransferCreated_doesNotPropagateException() {
        when(userRepository.findOwnersByTenantSchemaName(TENANT_ID))
                .thenReturn(List.of(owner()));
        when(employeeRepository.findByStoreId(DEST_STORE_ID)).thenReturn(List.of());
        doThrow(new RuntimeException("FCM unavailable"))
                .when(notificationPort).notifyUsers(any(), any(), any());

        // Must not throw — notification failures are best-effort
        org.junit.jupiter.api.Assertions.assertDoesNotThrow(
                () -> listener.onTransferCreated(makeEvent()));
    }
}
