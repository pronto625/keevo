package com.keevo.identity.role.application.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.keevo.identity.auth.domain.model.Tenant;
import com.keevo.identity.auth.domain.port.out.TenantRepository;
import com.keevo.identity.role.domain.model.CustomRole;
import com.keevo.identity.role.domain.port.out.CustomRoleStore;
import com.keevo.shared.application.port.AuditPort;
import com.keevo.shared.application.port.TransactionLockPort;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import com.keevo.shared.infrastructure.security.ShippedActorPermissions;
import com.keevo.shared.infrastructure.security.access.AccessStateChangePublisher;
import com.keevo.shared.module.EntitlementService;
import com.keevo.shared.module.application.ModuleCatalog;
import com.keevo.shared.module.application.PermissionCatalog;
import com.keevo.store.api.StoreQueryPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("CustomRoleService — owner-defined roles: validation, uniqueness, audit (Story 19.14)")
class CustomRoleServiceTest {

    private static final String SCHEMA = "kv_roles1";
    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID OWNER = UUID.randomUUID();
    private static final UUID STORE_A = UUID.randomUUID();

    private final CustomRoleStore store = mock(CustomRoleStore.class);
    private final TenantRepository tenants = mock(TenantRepository.class);
    private final EntitlementService entitlements = mock(EntitlementService.class);
    private final StoreQueryPort stores = mock(StoreQueryPort.class);
    private final AuditPort audit = mock(AuditPort.class);
    private final AccessStateChangePublisher accessStates = mock(AccessStateChangePublisher.class);
    private CustomRoleService service;

    @BeforeEach
    void setUp() {
        Tenant tenant = mock(Tenant.class);
        when(tenant.getId()).thenReturn(TENANT);
        when(tenants.findBySchemaName(SCHEMA)).thenReturn(Optional.of(tenant));
        when(entitlements.enabledModules(SCHEMA)).thenReturn(Set.of("core", "pos", "catalog", "expenses", "finance"));
        when(stores.namesByIds(eq(SCHEMA), anyCollection())).thenReturn(Map.of(STORE_A, "Boutique Akwa"));
        var permissions = ShippedActorPermissions.modulePermissions();
        PermissionCatalog catalog = new PermissionCatalog(permissions,
                new ModuleCatalog(new PathMatchingResourcePatternResolver()), entitlements);
        service = new CustomRoleService(store, tenants, catalog, permissions, stores, mock(TransactionLockPort.class),
                audit, new ObjectMapper(), Clock.fixed(Instant.parse("2026-11-11T08:00:00Z"), ZoneOffset.UTC),
                accessStates);
    }

    private static CustomRoleService.RoleInput input(String name, String base, List<String> permissions,
                                                     List<UUID> stores) {
        return new CustomRoleService.RoleInput(name, base, permissions, stores);
    }

    private static void refused(Runnable action, ErrorCode code, String message) {
        assertThatThrownBy(action::run).isInstanceOf(DomainException.class)
                .hasFieldOrPropertyWithValue("errorCode", code)
                .satisfies(e -> {
                    if (message != null) {
                        assertThat(e).extracting("userMessage").isEqualTo(message);
                    }
                });
    }

    @Test
    @DisplayName("creates « Caissier » and audits it in French (labels, never codes)")
    void create() {
        CustomRole role = service.create(SCHEMA, OWNER,
                input("  Caissier ", "EMPLOYEE", List.of("SALE_CREATE", "EXPENSE_VIEW"), null));

        assertThat(role.name()).isEqualTo("Caissier");
        assertThat(role.permissions()).containsExactlyInAnyOrder("SALE_CREATE", "EXPENSE_VIEW");
        assertThat(role.storeIds()).isNull();
        verify(store).insert(any(CustomRole.class), eq(OWNER));
        ArgumentCaptor<AuditPort.AuditEntry> entry = ArgumentCaptor.forClass(AuditPort.AuditEntry.class);
        verify(audit).record(entry.capture());
        assertThat(entry.getValue().action()).isEqualTo("ROLE_CREATED");
        assertThat(entry.getValue().valueAfter()).contains("Créer des ventes").contains("Voir les dépenses")
                .contains("Employé").contains("Toutes les boutiques").doesNotContain("SALE_CREATE");
    }

    @Test
    void validation() {
        refused(() -> service.create(SCHEMA, OWNER, input(" ", "EMPLOYEE", List.of(), null)),
                ErrorCode.VALIDATION_FAILED, CustomRoleService.NAME_INVALID);
        refused(() -> service.create(SCHEMA, OWNER, input("x".repeat(61), "EMPLOYEE", List.of(), null)),
                ErrorCode.VALIDATION_FAILED, CustomRoleService.NAME_INVALID);
        refused(() -> service.create(SCHEMA, OWNER, input("Patron", "OWNER", List.of(), null)),
                ErrorCode.VALIDATION_FAILED, CustomRoleService.BASE_ROLE_INVALID);
        refused(() -> service.create(SCHEMA, OWNER, input("Facturier", "MANAGER", List.of("INVOICE_VIEW"), null)),
                ErrorCode.VALIDATION_FAILED, CustomRoleService.PERMISSION_UNAVAILABLE); // invoicing not usable
        refused(() -> service.create(SCHEMA, OWNER, input("Contrôleur", "VIEWER", List.of("EXPENSE_CREATE"), null)),
                ErrorCode.VALIDATION_FAILED, CustomRoleService.VIEWER_WRITE);
        refused(() -> service.create(SCHEMA, OWNER, input("Akwa", "EMPLOYEE", List.of(), List.of())),
                ErrorCode.VALIDATION_FAILED, CustomRoleService.STORES_EMPTY);
        refused(() -> service.create(SCHEMA, OWNER, input("Akwa", "EMPLOYEE", List.of(),
                List.of(STORE_A, UUID.randomUUID()))), ErrorCode.VALIDATION_FAILED, CustomRoleService.STORE_UNKNOWN);
        verify(store, never()).insert(any(), any());
    }

    @Test
    void uniquenessAndCap() {
        when(store.nameTaken(TENANT, "Caissier", null)).thenReturn(true);
        refused(() -> service.create(SCHEMA, OWNER, input("Caissier", "EMPLOYEE", List.of(), null)),
                ErrorCode.ROLE_NAME_TAKEN, null);

        when(store.count(TENANT)).thenReturn(CustomRoleService.MAX_ROLES);
        refused(() -> service.create(SCHEMA, OWNER, input("Autre", "EMPLOYEE", List.of(), null)),
                ErrorCode.VALIDATION_FAILED, CustomRoleService.TOO_MANY);
    }

    @Test
    @DisplayName("update: base role immutable; a change evicts the company's cached rights")
    void update() {
        UUID id = UUID.randomUUID();
        CustomRole current = new CustomRole(id, TENANT, "Caissier", "EMPLOYEE", Set.of("SALE_CREATE"), null,
                Instant.EPOCH, Instant.EPOCH);
        when(store.findById(TENANT, id)).thenReturn(Optional.of(current));

        refused(() -> service.update(SCHEMA, OWNER, id, input("Caissier", "MANAGER", List.of(), null)),
                ErrorCode.VALIDATION_FAILED, CustomRoleService.BASE_ROLE_IMMUTABLE);

        when(store.memberCount(TENANT, id)).thenReturn(2);
        CustomRole.WithMembers updated = service.update(SCHEMA, OWNER, id,
                input("Caissier", null, List.of("SALE_CREATE", "PURCHASE_PRICE_VIEW"), List.of(STORE_A)));
        assertThat(updated.role().permissions()).contains("PURCHASE_PRICE_VIEW");
        assertThat(updated.role().storeIds()).containsExactly(STORE_A);
        assertThat(updated.memberCount()).isEqualTo(2);
        verify(store).update(any(CustomRole.class));
        verify(accessStates).tenantChanged(SCHEMA);

        refused(() -> service.update(SCHEMA, OWNER, UUID.randomUUID(), input("X", null, List.of(), null)),
                ErrorCode.ROLE_NOT_FOUND, null);
    }

    @Test
    void deleteRefusedWhileAssigned() {
        UUID id = UUID.randomUUID();
        when(store.findById(TENANT, id)).thenReturn(Optional.of(new CustomRole(id, TENANT, "Caissier", "EMPLOYEE",
                Set.of(), null, Instant.EPOCH, Instant.EPOCH)));
        when(store.memberCount(TENANT, id)).thenReturn(1);
        refused(() -> service.delete(SCHEMA, OWNER, id), ErrorCode.ROLE_IN_USE, null);

        when(store.memberCount(TENANT, id)).thenReturn(0);
        service.delete(SCHEMA, OWNER, id);
        verify(store).delete(TENANT, id);
        verify(accessStates).tenantChanged(SCHEMA);
    }

    @Test
    @DisplayName("built-in presets: Gérant, Employé, Lecteur with their default rights")
    void builtIns() {
        List<CustomRoleService.BuiltIn> presets = service.builtIns();

        assertThat(presets).extracting(CustomRoleService.BuiltIn::name).containsExactly("Gérant", "Employé", "Lecteur");
        assertThat(presets.get(1).permissions()).contains("SALE_CREATE").doesNotContain("PURCHASE_PRICE_VIEW");
        assertThat(presets.get(2).permissions()).allMatch(p -> p.endsWith("_VIEW"));
        verify(store, never()).nameTaken(any(), any(), isNull());
    }
}
