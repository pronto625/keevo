package com.keevo.shared.infrastructure.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.keevo.catalog.category.domain.event.CategoryCreatedEvent;
import com.keevo.crm.contact.domain.event.ClientCreatedEvent;
import com.keevo.catalog.product.domain.event.SalePriceOverriddenEvent;
import com.keevo.inventory.stock.domain.event.StockAdjustedEvent;
import com.keevo.inventory.stock.domain.entity.MovementType;
import com.keevo.commerce.sale.domain.event.DayClosedEvent;
import com.keevo.commerce.api.DayClosureSummary;
import com.keevo.commerce.sale.domain.event.SaleCorrectedEvent;
import com.keevo.commerce.sale.domain.event.SaleCancelledEvent;
import com.keevo.commerce.sale.domain.event.SaleCompletedEvent;
import com.keevo.inventory.counting.domain.event.InventorySessionCreatedEvent;
import com.keevo.inventory.counting.domain.event.InventoryValidatedEvent;
import com.keevo.inventory.counting.domain.model.InventoryScope;
import com.keevo.shared.application.port.AuditContext;
import com.keevo.shared.infrastructure.security.AuthDetails;
import com.keevo.store.store.domain.event.StoreUpdatedEvent;
import org.junit.jupiter.api.Nested;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import com.keevo.identity.auth.domain.model.UserAuthenticatedEvent;
import com.keevo.identity.auth.domain.model.UserRegisteredEvent;
import com.keevo.identity.auth.domain.model.PasswordResetRequestedEvent;
import com.keevo.identity.auth.domain.model.PasswordResetEvent;
import com.keevo.identity.auth.domain.model.UserMembershipInfo;
import com.keevo.identity.auth.domain.port.out.UserRepository;
import com.keevo.identity.employee.domain.event.EmployeePasswordSetByOwnerEvent;
import com.keevo.identity.employee.domain.event.EmployeeRoleChangedEvent;
import com.keevo.identity.employee.domain.event.EmployeeUpdatedEvent;
import com.keevo.identity.onboarding.domain.model.OnboardingCompletedEvent;
import com.keevo.identity.onboarding.domain.model.SectorType;
import com.keevo.shared.application.port.AuditPort;
import com.keevo.shared.infrastructure.persistence.TenantContext;
import org.junit.jupiter.api.AfterEach;
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

import static com.keevo.shared.infrastructure.web.AuditEntryMatchers.ANY;
import static com.keevo.shared.infrastructure.web.AuditEntryMatchers.entry;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AuditEventListenerTest — TDD tests for AuditEventListener.
 *
 * <p>RED → GREEN:
 * - RED: AuditEventListener constructor still takes no args / no AuditPort injection.
 * - GREEN: After wiring AuditPort + ObjectMapper in AuditEventListener.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AuditEventListener")
class AuditEventListenerTest {

    @Mock
    AuditPort auditPort;

    @Mock
    UserRepository userRepository;

    AuditEventListener listener;
    ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        listener = new AuditEventListener(auditPort, objectMapper, userRepository);
        TenantContext.clear(); // ensure clean thread state before each test
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear(); // always clean up thread state
    }

    // ── UserRegisteredEvent ───────────────────────────────────────────────────

    @Test
    @DisplayName("on(UserRegisteredEvent) calls auditPort.record() with USER_REGISTERED action")
    void onUserRegistered_callsAuditPort() {
        UUID userId   = UUID.randomUUID();
        UUID tenantId = UUID.randomUUID();
        UserRegisteredEvent event = UserRegisteredEvent.of(userId, tenantId, "KV-ABC123", "kv_abc123");

        listener.on(event);

        verify(auditPort).record(entry(userId, "kv_abc123", "USER_REGISTERED", "User", userId, null));
    }

    @Test
    @DisplayName("on(UserRegisteredEvent) sets TenantContext before calling auditPort.record() — public endpoint")
    void onUserRegistered_setsTenantContextBeforeRecord() {
        UUID userId   = UUID.randomUUID();
        UUID tenantId = UUID.randomUUID();
        UserRegisteredEvent event = UserRegisteredEvent.of(userId, tenantId, "KV-ABC123", "kv_abc123");

        // Verify TenantContext value AT THE MOMENT auditPort.record() is called using doAnswer
        doAnswer(inv -> {
            // Inside the invocation — TenantContext must already be set
            String currentTenantCtx = TenantContext.getCurrentTenant();
            assertThat(currentTenantCtx)
                    .as("TenantContext MUST be set to schemaName when auditPort.record() is called")
                    .isEqualTo("kv_abc123");
            return null;
        }).when(auditPort).record(any(AuditPort.AuditEntry.class));

        listener.on(event);

        // After the event handler completes, TenantContext MUST be cleared
        assertThat(TenantContext.getCurrentTenant())
                .as("TenantContext MUST be cleared in finally block after on(UserRegisteredEvent)")
                .isNull();
    }

    @Test
    @DisplayName("on(UserRegisteredEvent) clears TenantContext in finally block even if auditPort throws")
    void onUserRegistered_clearsTenantContext_evenOnException() {
        UUID userId   = UUID.randomUUID();
        UUID tenantId = UUID.randomUUID();
        UserRegisteredEvent event = UserRegisteredEvent.of(userId, tenantId, "KV-ABC123", "kv_abc123");

        org.mockito.Mockito.doThrow(new RuntimeException("DB error"))
                .when(auditPort).record(any(AuditPort.AuditEntry.class));

        // Should not propagate — listener logs error
        try {
            listener.on(event);
        } catch (RuntimeException ignored) {
            // Depending on implementation: swallowed or rethrown — either way, TenantContext must be cleared
        }

        assertThat(TenantContext.getCurrentTenant())
                .as("TenantContext MUST be cleared even when auditPort throws")
                .isNull();
    }

    // ── UserAuthenticatedEvent ────────────────────────────────────────────────

    @Test
    @DisplayName("on(UserAuthenticatedEvent) calls auditPort.record() with USER_AUTHENTICATED action")
    void onUserAuthenticated_callsAuditPort() {
        UUID userId = UUID.randomUUID();
        UserAuthenticatedEvent event = new UserAuthenticatedEvent(
                userId, "kv_xyz999", "OWNER", "192.168.1.1", Instant.now());

        listener.on(event);

        verify(auditPort).record(entry(userId, "kv_xyz999", "USER_AUTHENTICATED", "User", userId, null));
    }

    @Test
    @DisplayName("on(UserAuthenticatedEvent) sets TenantContext = event.tenantId() before record() — public endpoint")
    void onUserAuthenticated_setsTenantContextBeforeRecord() {
        UUID userId = UUID.randomUUID();
        UserAuthenticatedEvent event = new UserAuthenticatedEvent(
                userId, "kv_xyz999", "OWNER", null, Instant.now());

        doAnswer(inv -> {
            assertThat(TenantContext.getCurrentTenant())
                    .as("TenantContext MUST be set to event.tenantId() (schemaName) at record() call time")
                    .isEqualTo("kv_xyz999");
            return null;
        }).when(auditPort).record(any(AuditPort.AuditEntry.class));

        listener.on(event);

        assertThat(TenantContext.getCurrentTenant())
                .as("TenantContext MUST be cleared after on(UserAuthenticatedEvent)")
                .isNull();
    }

    @Test
    @DisplayName("on(UserAuthenticatedEvent) includes 'unknown' ip when ipAddress is null")
    void onUserAuthenticated_nullIpAddress_usesUnknown() {
        UUID userId = UUID.randomUUID();
        UserAuthenticatedEvent event = new UserAuthenticatedEvent(
                userId, "kv_xyz999", "OWNER", null, Instant.now());

        listener.on(event);

        // ip is request context → metadata (v1s-18-6); role stays in valueAfter (read by the app)
        AuditPort.AuditEntry e = captureEntry();
        assertThat(e.metadataJson()).contains("\"ip\":\"unknown\"");
        assertThat(e.valueAfter()).contains("\"role\":\"OWNER\"").doesNotContain("ip");
    }

    // ── OnboardingCompletedEvent ──────────────────────────────────────────────

    @Test
    @DisplayName("on(OnboardingCompletedEvent) calls auditPort.record() with ONBOARDING_COMPLETED action")
    void onOnboardingCompleted_callsAuditPort() {
        UUID actorId = UUID.randomUUID();
        OnboardingCompletedEvent event = new OnboardingCompletedEvent(
                "kv_aaa111", SectorType.CLOTHING, "Ma Boutique", 5, actorId, Instant.now());

        listener.on(event);

        verify(auditPort).record(entry(actorId, "kv_aaa111", "ONBOARDING_COMPLETED", "Tenant", actorId, null));
    }

    @Test
    @DisplayName("on(OnboardingCompletedEvent) does NOT touch TenantContext — authenticated endpoint")
    void onOnboardingCompleted_doesNotSetTenantContext() {
        // OnboardingCompleted fires on /api/v1/onboarding/complete (authenticated endpoint)
        // JwtAuthFilter has already set TenantContext — listener must NOT override it
        // TenantContext.set() equivalent
        TenantContext.setCurrentTenant("kv_already_set");

        UUID actorId = UUID.randomUUID();
        OnboardingCompletedEvent event = new OnboardingCompletedEvent(
                "kv_aaa111", SectorType.CLOTHING, "Ma Boutique", 5, actorId, Instant.now());

        listener.on(event);

        // TenantContext should remain as-is (not cleared by onboarding handler)
        assertThat(TenantContext.getCurrentTenant())
                .as("on(OnboardingCompletedEvent) must NOT clear TenantContext set by JwtAuthFilter")
                .isEqualTo("kv_already_set");

        TenantContext.clear(); // cleanup after test
    }

    // ── Story 14.11 — Employee events (authenticated endpoints) ──────────────

    @Test
    @DisplayName("on(EmployeeUpdatedEvent) calls auditPort.record() with EMPLOYEE_UPDATED action")
    void onEmployeeUpdated_callsAuditPort() {
        UUID actorId = UUID.randomUUID();
        UUID employeeId = UUID.randomUUID();
        EmployeeUpdatedEvent event = new EmployeeUpdatedEvent(
                actorId, "kv_abc123", employeeId,
                List.of("firstName", "phoneNumber"), Instant.now());

        listener.on(event);

        verify(auditPort).record(entry(actorId, "kv_abc123", "EMPLOYEE_UPDATED", "Employee", employeeId, null));
    }

    @Test
    @DisplayName("on(EmployeeRoleChangedEvent) calls auditPort.record() with before/after role")
    void onEmployeeRoleChanged_callsAuditPort() {
        UUID actorId = UUID.randomUUID();
        UUID employeeId = UUID.randomUUID();
        EmployeeRoleChangedEvent event = new EmployeeRoleChangedEvent(
                actorId, "kv_abc123", employeeId,
                "EMPLOYEE", "OWNER", Instant.now());

        listener.on(event);

        verify(auditPort).record(entry(actorId, "kv_abc123", "EMPLOYEE_ROLE_CHANGED", "Employee", employeeId));
    }

    @Test
    @DisplayName("on(EmployeePasswordSetByOwnerEvent) calls auditPort.record() with forcedReset=true")
    void onEmployeePasswordSetByOwner_callsAuditPort() {
        UUID actorId = UUID.randomUUID();
        UUID employeeId = UUID.randomUUID();
        EmployeePasswordSetByOwnerEvent event = new EmployeePasswordSetByOwnerEvent(
                actorId, "kv_abc123", employeeId, Instant.now());

        listener.on(event);

        verify(auditPort).record(entry(actorId, "kv_abc123", "EMPLOYEE_PASSWORD_SET_BY_OWNER", "Employee", employeeId, null));
    }

    // ── PasswordResetRequestedEvent (Story 14.12) ─────────────────────────

    @Test
    @DisplayName("on(PasswordResetRequestedEvent) audits in all user memberships")
    void onPasswordResetRequested_auditsInAllMemberships() {
        UUID userId = UUID.randomUUID();
        var m1 = new UserMembershipInfo("KV-AAA", "Tenant A", "OWNER", "kv_aaa");
        var m2 = new UserMembershipInfo("KV-BBB", "Tenant B", "EMPLOYEE", "kv_bbb");
        when(userRepository.findMembershipsWithTenantInfo(userId)).thenReturn(List.of(m1, m2));

        // Capture TenantContext at the moment record() is called — must match the
        // membership's schemaName (cross-tenant loop, D4).
        doAnswer(invocation -> {
            String currentTenant = TenantContext.getCurrentTenant();
            String expectedSchema = ((AuditPort.AuditEntry) invocation.getArgument(0)).tenantId();
            assertThat(currentTenant)
                    .as("TenantContext MUST be set to schemaName when auditPort.record() is called")
                    .isEqualTo(expectedSchema);
            return null;
        }).when(auditPort).record(any(AuditPort.AuditEntry.class));

        var event = new PasswordResetRequestedEvent(userId, Instant.now());
        listener.on(event);

        // Must audit in BOTH tenants
        verify(auditPort).record(entry(userId, "kv_aaa", "PASSWORD_RESET_REQUESTED", "User", userId, null));
        verify(auditPort).record(entry(userId, "kv_bbb", "PASSWORD_RESET_REQUESTED", "User", userId, null));

        // After the event handler completes, TenantContext MUST be cleared
        assertThat(TenantContext.getCurrentTenant())
                .as("TenantContext MUST be cleared after on(PasswordResetRequestedEvent)")
                .isNull();
    }

    @Test
    @DisplayName("on(PasswordResetEvent) audits in all user memberships")
    void onPasswordReset_auditsInAllMemberships() {
        UUID userId = UUID.randomUUID();
        var m1 = new UserMembershipInfo("KV-AAA", "Tenant A", "OWNER", "kv_aaa");
        when(userRepository.findMembershipsWithTenantInfo(userId)).thenReturn(List.of(m1));

        // Capture TenantContext at record() time — must equal schemaName
        doAnswer(invocation -> {
            String currentTenant = TenantContext.getCurrentTenant();
            String expectedSchema = ((AuditPort.AuditEntry) invocation.getArgument(0)).tenantId();
            assertThat(currentTenant)
                    .as("TenantContext MUST be set to schemaName when auditPort.record() is called")
                    .isEqualTo(expectedSchema);
            return null;
        }).when(auditPort).record(any(AuditPort.AuditEntry.class));

        var event = new PasswordResetEvent(userId, Instant.now());
        listener.on(event);

        verify(auditPort).record(entry(userId, "kv_aaa", "PASSWORD_RESET", "User", userId, null));

        // After the event handler completes, TenantContext MUST be cleared
        assertThat(TenantContext.getCurrentTenant())
                .as("TenantContext MUST be cleared after on(PasswordResetEvent)")
                .isNull();
    }

    @Test
    @DisplayName("on(PasswordResetEvent) continues loop even if one tenant's audit throws")
    void onPasswordReset_continuesLoopOnAuditFailure() {
        UUID userId = UUID.randomUUID();
        var m1 = new UserMembershipInfo("KV-AAA", "Tenant A", "OWNER", "kv_aaa");
        var m2 = new UserMembershipInfo("KV-BBB", "Tenant B", "EMPLOYEE", "kv_bbb");
        var m3 = new UserMembershipInfo("KV-CCC", "Tenant C", "OWNER", "kv_ccc");
        when(userRepository.findMembershipsWithTenantInfo(userId))
                .thenReturn(List.of(m1, m2, m3));

        // First tenant's audit throws — listener must catch, log, continue to next tenant
        doThrow(new RuntimeException("DB connection lost"))
                .when(auditPort).record(entry(userId, "kv_aaa", ANY, ANY, ANY));

        var event = new PasswordResetEvent(userId, Instant.now());
        // Must NOT throw — best-effort audit per tenant
        listener.on(event);

        // kv_aaa failed, but kv_bbb and kv_ccc must still be attempted
        verify(auditPort).record(entry(userId, "kv_aaa", "PASSWORD_RESET", "User", userId, null));
        verify(auditPort).record(entry(userId, "kv_bbb", "PASSWORD_RESET", "User", userId, null));
        verify(auditPort).record(entry(userId, "kv_ccc", "PASSWORD_RESET", "User", userId, null));

        // TenantContext MUST be cleared even after the exception path
        assertThat(TenantContext.getCurrentTenant())
                .as("TenantContext MUST be cleared even when audit throws")
                .isNull();
    }

    // ── Story v1s-18-6 — enrichment: source / device / store / label / metadata ──

    private AuditPort.AuditEntry captureEntry() {
        ArgumentCaptor<AuditPort.AuditEntry> captor = ArgumentCaptor.forClass(AuditPort.AuditEntry.class);
        verify(auditPort).record(captor.capture());
        return captor.getValue();
    }

    private static void authenticateEmployee(String firstName, UUID storeId) {
        var auth = new UsernamePasswordAuthenticationToken(
                UUID.randomUUID(), null, List.of(new SimpleGrantedAuthority("ROLE_EMPLOYEE")));
        auth.setDetails(AuthDetails.employee(firstName, storeId));
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    @Nested
    @DisplayName("v1s-18-6 enrichment")
    class Enrichment {

        private final UUID actorId = UUID.randomUUID();
        private final UUID STORE = UUID.randomUUID();

        @AfterEach
        void resetThreadState() {
            RequestContextHolder.resetRequestAttributes();
            SecurityContextHolder.clearContext();
        }

        @Test
        @DisplayName("REST write → source ONLINE, device from X-Device-Id, store fallback = employee store, actorLabel in metadata")
        void restWrite_isOnlineWithHeaderDevice() throws Exception {
            MockHttpServletRequest request = new MockHttpServletRequest();
            request.addHeader("X-Device-Id", "phone-42");
            RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
            UUID storeId = UUID.randomUUID();
            authenticateEmployee("Awa", storeId);
            UUID clientId = UUID.randomUUID();

            listener.on(new ClientCreatedEvent(clientId, "Alice", actorId, "kv_abc123", Instant.now()));

            AuditPort.AuditEntry e = captureEntry();
            assertThat(e.source()).isEqualTo("ONLINE");
            assertThat(e.deviceId()).isEqualTo("phone-42");
            assertThat(e.storeId()).isEqualTo(storeId);
            assertThat(e.entityLabel()).isEqualTo("Alice");
            assertThat(objectMapper.readTree(e.metadataJson()).get("actorLabel").asText()).isEqualTo("Awa");
        }

        @Test
        @DisplayName("write inside a sync push → source SYNC, device = push deviceId, label = category name")
        void syncWrite_isSyncWithPushDevice() {
            UUID categoryId = UUID.randomUUID();
            AuditContext.runAsSync("device-D", () -> {
                listener.on(new CategoryCreatedEvent(categoryId, "Pagnes", null, actorId, "kv_abc123", Instant.now()));
                return null;
            });

            AuditPort.AuditEntry e = captureEntry();
            assertThat(e.source()).isEqualTo("SYNC");
            assertThat(e.deviceId()).isEqualTo("device-D");
            assertThat(e.entityLabel()).isEqualTo("Pagnes");
            assertThat(AuditContext.current()).isEmpty();
        }

        @Test
        @DisplayName("scheduler write (no request) → source SYSTEM; DayClosed context moved to metadata, store column set")
        void systemWrite_dayClosed() throws Exception {
            UUID storeId = UUID.randomUUID();
            UUID closureId = UUID.randomUUID();
            Instant now = Instant.now();
            listener.on(new DayClosedEvent(closureId, storeId, actorId,
                    new DayClosureSummary(3, 15000, null, null, 0, 15000, 0, 0, 0), true, "kv_abc123", now, now.minusSeconds(3600), now));

            AuditPort.AuditEntry e = captureEntry();
            assertThat(e.source()).isEqualTo("SYSTEM");
            assertThat(e.deviceId()).isNull();
            assertThat(e.storeId()).isEqualTo(storeId);
            assertThat(e.valueAfter()).contains("totalSales").doesNotContain("storeId").doesNotContain("isAutomatic");
            var meta = objectMapper.readTree(e.metadataJson());
            assertThat(meta.get("isAutomatic").asBoolean()).isTrue();
            assertThat(meta.has("occurredAt")).isTrue();
        }

        @Test
        @DisplayName("StockAdjusted → app keys stay in valueAfter, storeId/notes move to metadata/store column, label = product")
        void stockAdjusted_splitsValuesAndContext() throws Exception {
            UUID storeId = UUID.randomUUID();
            UUID productId = UUID.randomUUID();
            listener.on(new StockAdjustedEvent(productId, null, storeId, MovementType.STOCK_ENTRY, 4, 6, 10,
                    actorId, "arrivage", "kv_abc123", "OWNER", "Moussa", "Boutique A", "Pagne wax", Instant.now()));

            AuditPort.AuditEntry e = captureEntry();
            var after = objectMapper.readTree(e.valueAfter());
            assertThat(after.get("movementType").asText()).isEqualTo("STOCK_ENTRY");
            assertThat(after.get("quantityChange").asInt()).isEqualTo(6);
            assertThat(after.get("quantityAfter").asInt()).isEqualTo(10);
            assertThat(after.has("storeId")).isFalse();
            assertThat(after.has("notes")).isFalse();
            assertThat(e.storeId()).isEqualTo(storeId);
            assertThat(e.entityLabel()).isEqualTo("Pagne wax");
            var meta = objectMapper.readTree(e.metadataJson());
            assertThat(meta.get("notes").asText()).isEqualTo("arrivage");
            assertThat(meta.get("actorLabel").asText()).as("event actor name when no auth").isEqualTo("Moussa");
        }

        @Test
        @DisplayName("v1s-16-23: TRANSFER_OUT → metadata destinationStoreName; TRANSFER_IN → sourceStoreName")
        void stockAdjusted_transferCarriesCounterpartStoreName() throws Exception {
            UUID storeId = UUID.randomUUID();
            listener.on(new StockAdjustedEvent(UUID.randomUUID(), null, storeId, MovementType.TRANSFER_OUT, 10, -3, 7,
                    actorId, null, "kv_abc123", "OWNER", "Moussa", "Boutique A", "Pagne wax", Instant.now(),
                    "Boutique B"));
            var meta = objectMapper.readTree(captureEntry().metadataJson());
            assertThat(meta.get("destinationStoreName").asText()).isEqualTo("Boutique B");
            assertThat(meta.has("sourceStoreName")).isFalse();

            org.mockito.Mockito.clearInvocations(auditPort);
            listener.on(new StockAdjustedEvent(UUID.randomUUID(), null, storeId, MovementType.TRANSFER_IN, 0, 3, 3,
                    actorId, null, "kv_abc123", "OWNER", "Moussa", "Boutique B", "Pagne wax", Instant.now(),
                    "Boutique A"));
            meta = objectMapper.readTree(captureEntry().metadataJson());
            assertThat(meta.get("sourceStoreName").asText()).isEqualTo("Boutique A");
            assertThat(meta.has("destinationStoreName")).isFalse();
        }

        @Test
        @DisplayName("v1s-16-23: transfer without counterpart name → no Vers/Depuis key")
        void stockAdjusted_transferWithoutCounterpart_noKey() throws Exception {
            listener.on(new StockAdjustedEvent(UUID.randomUUID(), null, UUID.randomUUID(), MovementType.TRANSFER_OUT,
                    10, -3, 7, actorId, null, "kv_abc123", "OWNER", "Moussa", "Boutique A", "Pagne wax", Instant.now()));
            var meta = objectMapper.readTree(captureEntry().metadataJson());
            assertThat(meta.has("destinationStoreName")).isFalse();
            assertThat(meta.has("sourceStoreName")).isFalse();
        }

        @Test
        @DisplayName("PriceOverridden → cataloguePrice before, appliedPrice/productName after, saleId in metadata")
        void priceOverridden_split() throws Exception {
            UUID saleId = UUID.randomUUID();
            listener.on(new SalePriceOverriddenEvent(UUID.randomUUID(), saleId, "Pagne wax", 5000, 4500,
                    actorId, "kv_abc123", Instant.now()));

            AuditPort.AuditEntry e = captureEntry();
            assertThat(objectMapper.readTree(e.valueBefore()).get("cataloguePrice").asInt()).isEqualTo(5000);
            var after = objectMapper.readTree(e.valueAfter());
            assertThat(after.get("appliedPrice").asInt()).isEqualTo(4500);
            assertThat(after.get("productName").asText()).isEqualTo("Pagne wax");
            assertThat(after.has("saleId")).isFalse();
            assertThat(objectMapper.readTree(e.metadataJson()).get("saleId").asText()).isEqualTo(saleId.toString());
            assertThat(e.entityLabel()).isEqualTo("Pagne wax");
        }

        @Test
        @DisplayName("SaleCorrected → valueBefore/After = snapshots, justification in metadata")
        void saleCorrected_snapshots() throws Exception {
            String before = "[{\"productId\":\"p1\",\"quantity\":2}]";
            String after = "[{\"productId\":\"p1\",\"quantity\":1}]";
            listener.on(new SaleCorrectedEvent(UUID.randomUUID(), actorId, "erreur de saisie client",
                    before, after, "kv_abc123", Instant.now(), STORE));

            AuditPort.AuditEntry e = captureEntry();
            assertThat(e.storeId()).isEqualTo(STORE);
            assertThat(e.valueBefore()).isEqualTo(before);
            assertThat(e.valueAfter()).isEqualTo(after);
            assertThat(objectMapper.readTree(e.metadataJson()).get("justification").asText())
                    .isEqualTo("erreur de saisie client");
        }

        @Test
        @DisplayName("StoreUpdated → store column = the store, label = new name, valueAfter has newName and name")
        void storeUpdated() throws Exception {
            UUID storeId = UUID.randomUUID();
            listener.on(new StoreUpdatedEvent(storeId, "Boutique Centre", actorId, "kv_abc123", Instant.now()));

            AuditPort.AuditEntry e = captureEntry();
            assertThat(e.storeId()).isEqualTo(storeId);
            assertThat(e.entityLabel()).isEqualTo("Boutique Centre");
            var after = objectMapper.readTree(e.valueAfter());
            assertThat(after.get("newName").asText()).isEqualTo("Boutique Centre");
            assertThat(after.get("name").asText()).isEqualTo("Boutique Centre");
        }

        // ── J7 / J10 — actions that used to leave no trace ──

        @Test
        @DisplayName("J7 StoreReactivated → STORE_REACTIVATED, store column + label = the store")
        void storeReactivated() {
            UUID storeId = UUID.randomUUID();
            listener.on(new com.keevo.store.store.domain.event.StoreReactivatedEvent(
                    storeId, "Boutique Akwa", actorId, "kv_abc123", Instant.now()));

            AuditPort.AuditEntry e = captureEntry();
            assertThat(e.action()).isEqualTo("STORE_REACTIVATED");
            assertThat(e.storeId()).isEqualTo(storeId);
            assertThat(e.entityLabel()).isEqualTo("Boutique Akwa");
        }

        @Test
        @DisplayName("J10 ProductUnarchived → PRODUCT_UNARCHIVED, label = product name")
        void productUnarchived() throws Exception {
            UUID productId = UUID.randomUUID();
            listener.on(new com.keevo.catalog.product.domain.event.ProductUnarchivedEvent(
                    productId, "Riz 5kg", "KEV-AAA", "kv_abc123", actorId, Instant.now()));

            AuditPort.AuditEntry e = captureEntry();
            assertThat(e.action()).isEqualTo("PRODUCT_UNARCHIVED");
            assertThat(e.entityType()).isEqualTo("Product");
            assertThat(e.entityId()).isEqualTo(productId);
            assertThat(e.entityLabel()).isEqualTo("Riz 5kg");
            assertThat(objectMapper.readTree(e.valueAfter()).get("archived").asBoolean()).isFalse();
        }

        @Test
        @DisplayName("J10 StockThresholdChanged → STOCK_THRESHOLD_UPDATED, before/after threshold")
        void stockThresholdChanged() throws Exception {
            UUID productId = UUID.randomUUID();
            listener.on(new com.keevo.inventory.stock.domain.event.StockThresholdChangedEvent(
                    productId, "Riz 5kg", 5, 12, actorId, "kv_abc123", Instant.now()));

            AuditPort.AuditEntry e = captureEntry();
            assertThat(e.action()).isEqualTo("STOCK_THRESHOLD_UPDATED");
            assertThat(e.entityLabel()).isEqualTo("Riz 5kg");
            assertThat(objectMapper.readTree(e.valueBefore()).get("threshold").asInt()).isEqualTo(5);
            assertThat(objectMapper.readTree(e.valueAfter()).get("threshold").asInt()).isEqualTo(12);
        }

        @Test
        @DisplayName("J10 ReportPreferencesUpdated → REPORT_PREFERENCES_UPDATED, label in French")
        void reportPreferencesUpdated() throws Exception {
            UUID prefsId = UUID.randomUUID();
            listener.on(new com.keevo.identity.onboarding.domain.model.ReportPreferencesUpdatedEvent(
                    prefsId, java.util.Map.of("eodReportTime", "20:00"),
                    java.util.Map.of("eodReportTime", "22:00"), actorId, "kv_abc123", Instant.now()));

            AuditPort.AuditEntry e = captureEntry();
            assertThat(e.action()).isEqualTo("REPORT_PREFERENCES_UPDATED");
            assertThat(e.entityType()).isEqualTo("Tenant");
            assertThat(e.entityLabel()).isEqualTo("Préférences des rapports");
            assertThat(objectMapper.readTree(e.valueAfter()).get("eodReportTime").asText()).isEqualTo("22:00");
        }

        // ── store_id column = the event's store, never the actor's home store (no auth set) ──

        @Test
        @DisplayName("SaleCompleted → store column = sale store")
        void saleCompleted_store() {
            listener.on(new SaleCompletedEvent(UUID.randomUUID(), actorId, "kv_abc123", STORE,
                    1000, 0, "[]", Instant.now()));
            assertThat(captureEntry().storeId()).isEqualTo(STORE);
        }

        @Test
        @DisplayName("InventorySessionCreated → store column = session store")
        void inventorySessionCreated_store() {
            listener.on(new InventorySessionCreatedEvent(UUID.randomUUID(), STORE, InventoryScope.FULL,
                    actorId, "kv_abc123", Instant.now()));
            assertThat(captureEntry().storeId()).isEqualTo(STORE);
        }

        @Test
        @DisplayName("SaleCancelled → store column = sale store")
        void saleCancelled_store() {
            listener.on(new SaleCancelledEvent(UUID.randomUUID(), actorId, "client a changé d'avis",
                    "[]", "kv_abc123", Instant.now(), STORE));
            assertThat(captureEntry().storeId()).isEqualTo(STORE);
        }

        @Test
        @DisplayName("InventoryValidated → store column = session store")
        void inventoryValidated_store() {
            listener.on(new InventoryValidatedEvent(UUID.randomUUID(), actorId, 3, "kv_abc123",
                    Instant.now(), STORE));
            assertThat(captureEntry().storeId()).isEqualTo(STORE);
        }

        @Test
        @DisplayName("a non-blank event actor name beats the resolver's role fallback label")
        void eventActorName_winsOverResolverLabel() throws Exception {
            var auth = new UsernamePasswordAuthenticationToken(
                    UUID.randomUUID(), null, List.of(new SimpleGrantedAuthority("ROLE_OWNER")));
            SecurityContextHolder.getContext().setAuthentication(auth); // no firstName → "Le propriétaire"
            listener.on(new StockAdjustedEvent(UUID.randomUUID(), null, STORE, MovementType.ADJUSTMENT, 4, -1, 3,
                    actorId, null, "kv_abc123", "OWNER", "Moussa", "Boutique A", "Pagne wax", Instant.now()));

            assertThat(objectMapper.readTree(captureEntry().metadataJson()).get("actorLabel").asText())
                    .isEqualTo("Moussa");
        }

        @Test
        @DisplayName("events without a readable name never use a UUID as label")
        void noUuidLabel() {
            listener.on(new EmployeePasswordSetByOwnerEvent(actorId, "kv_abc123", UUID.randomUUID(), Instant.now()));

            AuditPort.AuditEntry e = captureEntry();
            assertThat(e.entityLabel()).isNull();
        }
    }
}
