package com.keevo.shared.module.application;

import com.keevo.shared.application.port.AuditPort;
import com.keevo.shared.application.port.TransactionLockPort;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.infrastructure.persistence.TenantContext;
import com.keevo.shared.module.ModuleLifecycle;
import com.keevo.shared.module.ModuleLifecycle.ActivationRequest;
import com.keevo.shared.module.ModuleLifecycle.ActivationResult;
import com.keevo.shared.module.ModuleLifecycle.Actor;
import com.keevo.shared.module.ModuleProvisioner;
import com.keevo.shared.module.ModuleProvisioningException;
import com.keevo.shared.module.ModuleState;
import com.keevo.shared.module.ProvisioningResult;
import com.keevo.shared.module.TenantModuleStatus;
import com.keevo.shared.module.TestModuleCatalogs;
import com.keevo.shared.module.application.port.TenantModuleLifecyclePort;
import com.keevo.shared.module.application.port.TenantModuleStatePort;
import com.keevo.shared.module.infrastructure.RequestScopedActiveModulesCache;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Story 13.8a — {@link ModuleLifecycleService} on an in-memory {@code tenant_modules} (real registry and resolver on
 * {@link TestModuleCatalogs#lifecycleCatalog()}), provisioner / lock / audit mocked. No transaction here: the
 * after-commit audit runs immediately.
 */
class ModuleLifecycleServiceTest {

    private static final String SCHEMA = "kv_lcu001";
    private static final UUID TENANT = UUID.randomUUID();
    private static final String CODE = "KV-LCU001";
    private static final UUID ADMIN = UUID.randomUUID();
    private static final Actor SUPER_ADMIN = new Actor(ADMIN, true);
    private static final Instant NOW = Instant.parse("2026-10-30T10:00:00Z");

    private final InMemoryRows rows = new InMemoryRows();
    private final ModuleProvisioner provisioner = mock(ModuleProvisioner.class);
    private final TransactionLockPort locks = mock(TransactionLockPort.class);
    private final AuditPort audit = mock(AuditPort.class);
    private ModuleLifecycle lifecycle;

    /** {@code tenant_modules} of one tenant, both read (registry) and write (lifecycle) sides. */
    static final class InMemoryRows implements TenantModuleStatePort, TenantModuleLifecyclePort {

        final Map<String, StoredRow> stored = new TreeMap<>();
        final List<String> writes = new ArrayList<>();
        final List<String> events = new ArrayList<>();

        @Override
        public Optional<Map<String, Row>> findRows(String tenantSchema) {
            if (!SCHEMA.equals(tenantSchema)) {
                return Optional.empty();
            }
            Map<String, Row> out = new LinkedHashMap<>();
            stored.forEach((id, r) -> out.put(id, new Row(r.status(), r.expiresAt(), r.graceUntil(), r.tier())));
            return Optional.of(out);
        }

        @Override
        public Optional<TenantRef> findTenant(UUID tenantId) {
            events.add("findTenant");
            return TENANT.equals(tenantId) ? Optional.of(new TenantRef(TENANT, CODE, SCHEMA)) : Optional.empty();
        }

        @Override
        public Map<String, StoredRow> findStoredRows(String tenantSchema) {
            return SCHEMA.equals(tenantSchema) ? Map.copyOf(stored) : Map.of();
        }

        @Override
        public UUID upsertActivated(UUID tenantId, String moduleId, TenantModuleStatus status, String tier,
                                    Instant activatedAt, boolean keepActivatedAt, Instant expiresAt,
                                    Instant graceUntil) {
            StoredRow old = stored.get(moduleId);
            UUID id = old != null ? old.id() : UUID.randomUUID();
            Instant activated = keepActivatedAt && old != null && old.activatedAt() != null ? old.activatedAt()
                    : activatedAt;
            stored.put(moduleId, new StoredRow(id, status, tier, activated, expiresAt, graceUntil, null,
                    old != null ? old.provisionedVersion() : null));
            writes.add(moduleId);
            return id;
        }

        @Override
        public UUID upsertDisabled(UUID tenantId, String moduleId, String tier, Instant disabledAt) {
            StoredRow old = stored.get(moduleId);
            UUID id = old != null ? old.id() : UUID.randomUUID();
            stored.put(moduleId, old != null
                    ? new StoredRow(id, TenantModuleStatus.DISABLED, old.tier(), old.activatedAt(), old.expiresAt(),
                    old.graceUntil(), disabledAt, old.provisionedVersion())
                    : new StoredRow(id, TenantModuleStatus.DISABLED, tier, null, null, null, disabledAt, null));
            writes.add(moduleId);
            return id;
        }

        void put(String moduleId, TenantModuleStatus status, Instant expiresAt, Instant graceUntil) {
            stored.put(moduleId, new StoredRow(UUID.randomUUID(), status, "STANDARD", null, expiresAt, graceUntil,
                    null, null));
        }
    }

    @BeforeEach
    void setUp() {
        ModuleCatalog catalog = TestModuleCatalogs.lifecycleCatalog();
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        ModuleRegistryService registry = new ModuleRegistryService(catalog, rows,
                new RequestScopedActiveModulesCache(), clock);
        DependencyResolverService resolver = new DependencyResolverService(catalog, registry,
                new EntitlementActivationEligibility());
        when(provisioner.provision(anyString(), anyString()))
                .thenAnswer(inv -> new ProvisioningResult(inv.getArgument(1), null, "1", 1));
        lifecycle = new ModuleLifecycleService(catalog, registry, resolver, provisioner, rows, locks, audit, clock);
    }

    @AfterEach
    void clearContext() {
        TenantContext.clear();
    }

    private static String code(Runnable call) {
        try {
            call.run();
            return null;
        } catch (DomainException e) {
            return e.getDomainCode();
        }
    }

    private List<AuditPort.AuditEntry> auditEntries(int expected) {
        ArgumentCaptor<AuditPort.AuditEntry> captor = ArgumentCaptor.forClass(AuditPort.AuditEntry.class);
        verify(audit, times(expected)).record(captor.capture());
        return captor.getAllValues();
    }

    // ── activation ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("cascade: inactive dependency first — lock, then row + provision per step in plan order, audit each")
    void cascade() {
        rows.put("lcbase", TenantModuleStatus.DISABLED, null, null);
        rows.put("lctop", TenantModuleStatus.DISABLED, null, null);

        ActivationResult result = lifecycle.activate(TENANT, "lctop", ActivationRequest.active(), SUPER_ADMIN);

        assertThat(result.activated()).extracting(ModuleLifecycle.ModuleRef::displayName)
                .containsExactly("Base de test", "Module supérieur");
        assertThat(result.status()).isEqualTo(TenantModuleStatus.ACTIVE);
        assertThat(rows.writes).containsExactly("lcbase", "lctop");
        assertThat(rows.stored.get("lcbase").status()).isEqualTo(TenantModuleStatus.ACTIVE);
        assertThat(rows.stored.get("lctop").activatedAt()).isEqualTo(NOW);
        assertThat(rows.stored.get("lctop").disabledAt()).isNull();
        InOrder order = inOrder(locks, provisioner);
        order.verify(locks).lock("module-lifecycle:" + TENANT);
        order.verify(provisioner).provision(SCHEMA, "lcbase");
        order.verify(provisioner).provision(SCHEMA, "lctop");

        List<AuditPort.AuditEntry> entries = auditEntries(2);
        assertThat(entries).extracting(AuditPort.AuditEntry::action).containsOnly("MODULE_ACTIVATED");
        assertThat(entries).extracting(AuditPort.AuditEntry::entityLabel)
                .containsExactly("Base de test", "Module supérieur");
        AuditPort.AuditEntry top = entries.get(1);
        assertThat(top.actorId()).isEqualTo(ADMIN);
        assertThat(top.tenantId()).isEqualTo(SCHEMA);
        assertThat(top.entityId()).isEqualTo(rows.stored.get("lctop").id());
        assertThat(top.valueAfter()).contains("Module supérieur", "Actif")
                .doesNotContain("lctop", TENANT.toString(), SCHEMA, CODE, "entreprise");
        assertThat(TenantContext.getCurrentTenant()).isNull(); // restored after the audit write
    }

    @Test
    @DisplayName("audit is written in the tenant schema context, then the caller's context is restored")
    void auditTenantContext() {
        TenantContext.setCurrentTenant("public");
        List<String> seen = new ArrayList<>();
        doAnswer(inv -> seen.add(TenantContext.getCurrentTenant())).when(audit).record(any(AuditPort.AuditEntry.class));

        lifecycle.activate(TENANT, "lcpaid", new ActivationRequest(TenantModuleStatus.ACTIVE,
                NOW.plus(Duration.ofDays(30))), SUPER_ADMIN);

        assertThat(seen).containsExactly(SCHEMA);
        assertThat(TenantContext.getCurrentTenant()).isEqualTo("public");
    }

    @Test
    @DisplayName("paid module granted by a super-admin: ACTIVE, expiry kept, grace = expiry + 7 days, tier STANDARD")
    void paidGranted() {
        Instant expiry = NOW.plus(Duration.ofDays(30));

        ActivationResult result = lifecycle.activate(TENANT, "lcpaid",
                new ActivationRequest(TenantModuleStatus.ACTIVE, expiry), SUPER_ADMIN);

        TenantModuleLifecyclePort.StoredRow row = rows.stored.get("lcpaid");
        assertThat(row.status()).isEqualTo(TenantModuleStatus.ACTIVE);
        assertThat(row.expiresAt()).isEqualTo(expiry);
        assertThat(row.graceUntil()).isEqualTo(NOW.plus(Duration.ofDays(37)));
        assertThat(row.tier()).isEqualTo("STANDARD");
        assertThat(result.expiresAt()).isEqualTo(expiry);
        assertThat(auditEntries(1).get(0).valueAfter()).contains("finAbonnement", "29/11/2026");
    }

    @Test
    @DisplayName("paid dependency of a free target gets the request's status/expiry; the free target stays without end")
    void paidDependency() {
        rows.put("lcbuyer", TenantModuleStatus.DISABLED, null, null);
        Instant expiry = NOW.plus(Duration.ofDays(14));

        lifecycle.activate(TENANT, "lcbuyer", new ActivationRequest(TenantModuleStatus.TRIAL, expiry), SUPER_ADMIN);

        assertThat(rows.writes).containsExactly("lcpaid", "lcbuyer");
        assertThat(rows.stored.get("lcpaid").status()).isEqualTo(TenantModuleStatus.TRIAL);
        assertThat(rows.stored.get("lcpaid").expiresAt()).isEqualTo(expiry);
        assertThat(rows.stored.get("lcbuyer").status()).isEqualTo(TenantModuleStatus.ACTIVE);
        assertThat(rows.stored.get("lcbuyer").expiresAt()).isNull();
        assertThat(rows.stored.get("lcbuyer").graceUntil()).isNull();
    }

    @Test
    @DisplayName("repair plan, free target without a row: only the dependency is written; result ACTIVE/null/null (no NPE)")
    void repairPlanTargetWithoutRow() {
        rows.put("lcbase", TenantModuleStatus.DISABLED, null, null); // lctop: no row → active by the free rule

        ActivationResult result = lifecycle.activate(TENANT, "lctop", ActivationRequest.active(), SUPER_ADMIN);

        assertThat(rows.writes).containsExactly("lcbase");
        assertThat(result).isEqualTo(new ActivationResult(
                List.of(new ModuleLifecycle.ModuleRef("lcbase", "Base de test")), TenantModuleStatus.ACTIVE, null,
                null));
        verify(provisioner).provision(SCHEMA, "lctop");

        // same with a paid dependency (lcbuyer requires lcpaid)
        ActivationResult paid = lifecycle.activate(TENANT, "lcbuyer",
                new ActivationRequest(TenantModuleStatus.ACTIVE, NOW.plus(Duration.ofDays(30))), SUPER_ADMIN);
        assertThat(paid.activated()).extracting(ModuleLifecycle.ModuleRef::moduleId).containsExactly("lcpaid");
        assertThat(paid.status()).isEqualTo(TenantModuleStatus.ACTIVE);
        assertThat(paid.expiresAt()).isNull();
    }

    @Test
    @DisplayName("expiry rules: paid target / dependency without end → 422 naming it; free target with end or TRIAL → 422")
    void expiryRules() {
        DomainException paidTarget = (DomainException) org.assertj.core.api.Assertions.catchThrowable(() ->
                lifecycle.activate(TENANT, "lcpaid", ActivationRequest.active(), SUPER_ADMIN));
        assertThat(paidTarget.getDomainCode()).isEqualTo("VALIDATION_ERROR");
        assertThat(String.valueOf(paidTarget.getDetails().get("expiresAt")))
                .isEqualTo("Une date de fin est requise pour un module payant (« Module payant »)");

        rows.put("lcbuyer", TenantModuleStatus.DISABLED, null, null);
        assertThat(code(() -> lifecycle.activate(TENANT, "lcbuyer", ActivationRequest.active(), SUPER_ADMIN)))
                .isEqualTo("VALIDATION_ERROR"); // its paid dependency would be written without an end

        rows.put("lcbase", TenantModuleStatus.DISABLED, null, null);
        DomainException freeEnd = (DomainException) org.assertj.core.api.Assertions.catchThrowable(() ->
                lifecycle.activate(TENANT, "lctop", new ActivationRequest(TenantModuleStatus.ACTIVE,
                        NOW.plus(Duration.ofDays(5))), SUPER_ADMIN));
        assertThat(freeEnd.getDomainCode()).isEqualTo("VALIDATION_ERROR");
        assertThat(String.valueOf(freeEnd.getDetails().get("expiresAt"))).contains("« Module supérieur »", "gratuit");
        assertThat(code(() -> lifecycle.activate(TENANT, "lcplain", new ActivationRequest(TenantModuleStatus.TRIAL,
                NOW.plus(Duration.ofDays(5))), SUPER_ADMIN))).isEqualTo("VALIDATION_ERROR");
        assertThat(code(() -> lifecycle.activate(TENANT, "lcpaid", new ActivationRequest(TenantModuleStatus.TRIAL,
                null), SUPER_ADMIN))).isEqualTo("VALIDATION_ERROR"); // TRIAL needs an end
        assertThat(code(() -> lifecycle.activate(TENANT, "lcpaid", new ActivationRequest(TenantModuleStatus.ACTIVE,
                NOW.plus(Duration.ofDays(3653 + 2))), SUPER_ADMIN))).isEqualTo("VALIDATION_ERROR"); // > 10 years
        assertThat(lifecycle.activate(TENANT, "lcpaid", new ActivationRequest(TenantModuleStatus.ACTIVE,
                NOW.plus(Duration.ofDays(3650))), SUPER_ADMIN).activated()).hasSize(1); // 10 years: accepted
        assertThat(rows.writes).containsExactly("lcpaid");
    }

    @Test
    @DisplayName("activated_at is set on inactive → active only; a renewal of an active module keeps it")
    void activatedAtKept() {
        Instant first = NOW.minus(Duration.ofDays(40));
        rows.stored.put("lcpaid", new TenantModuleLifecyclePort.StoredRow(UUID.randomUUID(),
                TenantModuleStatus.ACTIVE, "STANDARD", first, NOW.plus(Duration.ofDays(2)),
                NOW.plus(Duration.ofDays(9)), null, "1"));

        lifecycle.activate(TENANT, "lcpaid", new ActivationRequest(TenantModuleStatus.ACTIVE,
                NOW.plus(Duration.ofDays(60))), SUPER_ADMIN);
        assertThat(rows.stored.get("lcpaid").activatedAt()).isEqualTo(first);

        rows.put("lcbase", TenantModuleStatus.DISABLED, null, null);
        lifecycle.activate(TENANT, "lcbase", ActivationRequest.active(), SUPER_ADMIN);
        assertThat(rows.stored.get("lcbase").activatedAt()).isEqualTo(NOW);
    }

    @Test
    @DisplayName("already enabled target: its dependencies active by the free rule are provisioned first, then the target")
    void alreadyEnabledProvisionsFreeDependencies() {
        ActivationResult result = lifecycle.activate(TENANT, "lctop", ActivationRequest.active(), SUPER_ADMIN);

        assertThat(result.activated()).isEmpty();
        InOrder order = inOrder(provisioner);
        order.verify(provisioner).provision(SCHEMA, "lcbase");
        order.verify(provisioner).provision(SCHEMA, "lctop");
        assertThat(rows.writes).isEmpty();
    }

    @Test
    @DisplayName("the per-tenant lock (tenant UUID) is the first statement, before the tenant is read")
    void lockFirst() {
        doAnswer(inv -> rows.events.add("lock:" + inv.getArgument(0))).when(locks).lock(anyString());

        lifecycle.activate(TENANT, "lcplain", null, SUPER_ADMIN);
        lifecycle.deactivate(TENANT, "lcplain", SUPER_ADMIN);

        assertThat(rows.events).containsExactly("lock:module-lifecycle:" + TENANT, "findTenant",
                "lock:module-lifecycle:" + TENANT, "findTenant");
    }

    @Test
    @DisplayName("a failing audit write does not fail the committed activation")
    void auditFailureSwallowed() {
        doAnswer(inv -> {
            throw new IllegalStateException("audit down");
        }).when(audit).record(any(AuditPort.AuditEntry.class));
        rows.put("lcbase", TenantModuleStatus.DISABLED, null, null);

        assertThat(lifecycle.activate(TENANT, "lcbase", null, SUPER_ADMIN).activated()).hasSize(1);
        assertThat(lifecycle.deactivate(TENANT, "lcplain", SUPER_ADMIN).status())
                .isEqualTo(TenantModuleStatus.DISABLED);
    }

    @Test
    @DisplayName("paid module by a non-super-admin actor: blocked (free-only eligibility), nothing written")
    void paidRefusedForOtherActors() {
        DomainException e = (DomainException) org.assertj.core.api.Assertions.catchThrowable(() ->
                lifecycle.activate(TENANT, "lcbuyer", ActivationRequest.active(), new Actor(ADMIN, false)));

        assertThat(e.getDomainCode()).isEqualTo("MODULE_ACTIVATION_BLOCKED");
        assertThat(String.valueOf(e.getDetails().get("reasons"))).contains("« Module payant »")
                .doesNotContain("lcpaid");
        assertThat(rows.writes).isEmpty();
        verifyNoInteractions(provisioner, audit);
    }

    @Test
    @DisplayName("already enabled free module without a row: idempotent, no row written, no audit, tables ensured")
    void alreadyEnabled() {
        ActivationResult result = lifecycle.activate(TENANT, "lcplain", ActivationRequest.active(), SUPER_ADMIN);

        assertThat(result.activated()).isEmpty();
        assertThat(result.status()).isEqualTo(TenantModuleStatus.ACTIVE);
        assertThat(rows.writes).isEmpty();
        verify(provisioner).provision(SCHEMA, "lcplain"); // tables ensured (no-op when up to date)
        verifyNoInteractions(audit);
    }

    @Test
    @DisplayName("already enabled, same status and expiry: idempotent; a new expiry refreshes the row only")
    void alreadyEnabledRefresh() {
        Instant expiry = NOW.plus(Duration.ofDays(30));
        lifecycle.activate(TENANT, "lcpaid", new ActivationRequest(TenantModuleStatus.ACTIVE, expiry), SUPER_ADMIN);
        rows.writes.clear();

        assertThat(lifecycle.activate(TENANT, "lcpaid", new ActivationRequest(TenantModuleStatus.ACTIVE, expiry),
                SUPER_ADMIN).activated()).isEmpty();
        assertThat(rows.writes).isEmpty();

        Instant later = NOW.plus(Duration.ofDays(60));
        ActivationResult renewed = lifecycle.activate(TENANT, "lcpaid",
                new ActivationRequest(TenantModuleStatus.ACTIVE, later), SUPER_ADMIN);
        assertThat(renewed.activated()).extracting(ModuleLifecycle.ModuleRef::moduleId).containsExactly("lcpaid");
        assertThat(rows.stored.get("lcpaid").expiresAt()).isEqualTo(later);
    }

    @Test
    @DisplayName("read-only module (expired, in grace): activation renews its row")
    void renewReadOnly() {
        rows.put("lcpaid", TenantModuleStatus.ACTIVE, NOW.minusSeconds(3600), NOW.plus(Duration.ofDays(6)));
        Instant expiry = NOW.plus(Duration.ofDays(30));

        ActivationResult result = lifecycle.activate(TENANT, "lcpaid",
                new ActivationRequest(TenantModuleStatus.ACTIVE, expiry), SUPER_ADMIN);

        assertThat(result.activated()).hasSize(1);
        assertThat(rows.stored.get("lcpaid").graceUntil()).isEqualTo(expiry.plus(Duration.ofDays(7)));
    }

    @Test
    @DisplayName("provisioning failure → MODULE_PROVISIONING_FAILED, French message without technical detail, no audit")
    void provisioningFailure() {
        when(provisioner.provision(SCHEMA, "lcpaid"))
                .thenThrow(new ModuleProvisioningException("lcpaid", SCHEMA, "ERROR/42601", null));

        DomainException e = (DomainException) org.assertj.core.api.Assertions.catchThrowable(() ->
                lifecycle.activate(TENANT, "lcpaid", new ActivationRequest(TenantModuleStatus.ACTIVE,
                        NOW.plus(Duration.ofDays(30))), SUPER_ADMIN));

        assertThat(e.getDomainCode()).isEqualTo("MODULE_PROVISIONING_FAILED");
        assertThat(e.getMessage()).contains("Module payant", "n'a pas été activé", "réessayer")
                .doesNotContain("42601", SCHEMA, "lcpaid", "rien n'a été modifié");
        verifyNoInteractions(audit);
    }

    @Test
    @DisplayName("refusals: unknown tenant, unknown / blank / platform module, bad status, past expiry — nothing written")
    void refusals() {
        assertThat(code(() -> lifecycle.activate(UUID.randomUUID(), "lcplain", null, SUPER_ADMIN)))
                .isEqualTo("TENANT_NOT_FOUND");
        assertThat(code(() -> lifecycle.activate(TENANT, "ghost", null, SUPER_ADMIN))).isEqualTo("MODULE_NOT_FOUND");
        assertThat(code(() -> lifecycle.activate(TENANT, " ", null, SUPER_ADMIN))).isEqualTo("MODULE_NOT_FOUND");
        assertThat(code(() -> lifecycle.activate(TENANT, "lcadmin", null, SUPER_ADMIN)))
                .isEqualTo("MODULE_NOT_FOUND");
        assertThat(code(() -> lifecycle.activate(TENANT, "lcplain",
                new ActivationRequest(TenantModuleStatus.EXPIRED, null), SUPER_ADMIN))).isEqualTo("VALIDATION_ERROR");
        assertThat(code(() -> lifecycle.activate(TENANT, "lcplain",
                new ActivationRequest(TenantModuleStatus.ACTIVE, NOW), SUPER_ADMIN))).isEqualTo("VALIDATION_ERROR");
        assertThat(code(() -> lifecycle.deactivate(UUID.randomUUID(), "lcplain", SUPER_ADMIN)))
                .isEqualTo("TENANT_NOT_FOUND");
        assertThat(code(() -> lifecycle.deactivate(TENANT, "ghost", SUPER_ADMIN))).isEqualTo("MODULE_NOT_FOUND");
        verifyNoInteractions(provisioner, audit);
        assertThat(rows.writes).isEmpty();
        org.mockito.Mockito.clearInvocations(locks);
        assertThat(code(() -> lifecycle.activate(null, "lcplain", null, SUPER_ADMIN))).isEqualTo("TENANT_NOT_FOUND");
        verifyNoInteractions(locks); // a null id never reaches the database
    }

    @Test
    @DisplayName("activating core: idempotent, never a row")
    void activateCore() {
        assertThat(lifecycle.activate(TENANT, "core", null, SUPER_ADMIN).activated()).isEmpty();
        assertThat(rows.writes).isEmpty();
    }

    // ── deactivation ────────────────────────────────────────────────────────

    @Test
    @DisplayName("deactivate a free module without dependents: row DISABLED + disabled_at, audit, no provisioning")
    void deactivate() {
        ModuleLifecycle.DeactivationResult result = lifecycle.deactivate(TENANT, "lcplain", SUPER_ADMIN);

        assertThat(result).isEqualTo(new ModuleLifecycle.DeactivationResult("lcplain", "Module simple",
                TenantModuleStatus.DISABLED));
        TenantModuleLifecyclePort.StoredRow row = rows.stored.get("lcplain");
        assertThat(row.status()).isEqualTo(TenantModuleStatus.DISABLED);
        assertThat(row.disabledAt()).isEqualTo(NOW);
        verify(locks).lock("module-lifecycle:" + TENANT);
        verify(provisioner, never()).provision(anyString(), anyString());
        AuditPort.AuditEntry entry = auditEntries(1).get(0);
        assertThat(entry.action()).isEqualTo("MODULE_DEACTIVATED");
        assertThat(entry.valueAfter()).contains("Module simple", "Désactivé", "Conservées")
                .doesNotContain("lcplain", CODE, "entreprise");
    }

    @Test
    @DisplayName("deactivate with active dependents → 409 MODULE_HAS_DEPENDENTS listing them by display name")
    void deactivateWithDependents() {
        DomainException e = (DomainException) org.assertj.core.api.Assertions.catchThrowable(() ->
                lifecycle.deactivate(TENANT, "lcbase", SUPER_ADMIN));

        assertThat(e.getDomainCode()).isEqualTo("MODULE_HAS_DEPENDENTS");
        assertThat(String.valueOf(e.getDetails().get("dependents")))
                .isEqualTo("« Module cassé », « Module supérieur »");
        assertThat(rows.writes).isEmpty();
        verifyNoInteractions(audit);
    }

    @Test
    @DisplayName("deactivate core / a core-category module → CORE_MODULE_REQUIRED")
    void deactivateCore() {
        assertThat(code(() -> lifecycle.deactivate(TENANT, "core", SUPER_ADMIN))).isEqualTo("CORE_MODULE_REQUIRED");
        assertThat(code(() -> lifecycle.deactivate(TENANT, "lcsync", SUPER_ADMIN)))
                .isEqualTo("CORE_MODULE_REQUIRED");
        assertThat(rows.writes).isEmpty();
    }

    @Test
    @DisplayName("already disabled → idempotent, nothing written; a paid module without a row is already disabled")
    void deactivateIdempotent() {
        rows.put("lcplain", TenantModuleStatus.DISABLED, null, null);

        assertThat(lifecycle.deactivate(TENANT, "lcplain", SUPER_ADMIN).status())
                .isEqualTo(TenantModuleStatus.DISABLED);
        assertThat(lifecycle.deactivate(TENANT, "lcpaid", SUPER_ADMIN).status())
                .isEqualTo(TenantModuleStatus.DISABLED);
        assertThat(rows.writes).isEmpty();
        verifyNoInteractions(audit);
    }

    @Test
    @DisplayName("deactivate keeps a paid row's expiry columns; reactivation clears disabled_at")
    void deactivateThenReactivate() {
        rows.put("lcbuyer", TenantModuleStatus.DISABLED, null, null); // else it requires lcpaid (free rule)
        Instant expiry = NOW.plus(Duration.ofDays(30));
        lifecycle.activate(TENANT, "lcpaid", new ActivationRequest(TenantModuleStatus.ACTIVE, expiry), SUPER_ADMIN);
        lifecycle.deactivate(TENANT, "lcpaid", SUPER_ADMIN);
        assertThat(rows.stored.get("lcpaid").expiresAt()).isEqualTo(expiry);

        lifecycle.activate(TENANT, "lcpaid", new ActivationRequest(TenantModuleStatus.ACTIVE, expiry), SUPER_ADMIN);

        assertThat(rows.stored.get("lcpaid").status()).isEqualTo(TenantModuleStatus.ACTIVE);
        assertThat(rows.stored.get("lcpaid").disabledAt()).isNull();
    }

    // ── reads ───────────────────────────────────────────────────────────────

    @Test
    @DisplayName("catalogue hides platform modules; tenant view: state + stored status, core never with a row")
    void reads() {
        assertThat(lifecycle.catalogue()).extracting(m -> m.id())
                .containsExactly("core", "lcbase", "lcbroken", "lcbuyer", "lcpaid", "lcplain", "lcsync", "lctop");
        rows.put("lcpaid", TenantModuleStatus.ACTIVE, NOW.minusSeconds(60), NOW.plus(Duration.ofDays(7)));

        List<ModuleLifecycle.TenantModuleView> views = lifecycle.tenantModules(TENANT);

        assertThat(views).extracting(ModuleLifecycle.TenantModuleView::moduleId)
                .containsExactly("core", "lcbase", "lcbroken", "lcbuyer", "lcpaid", "lcplain", "lcsync", "lctop");
        ModuleLifecycle.TenantModuleView paid = views.get(4);
        assertThat(paid.state()).isEqualTo(ModuleState.READ_ONLY);
        assertThat(paid.status()).isEqualTo(TenantModuleStatus.ACTIVE);
        assertThat(paid.displayName()).isEqualTo("Module payant");
        assertThat(views.get(1).state()).isEqualTo(ModuleState.ENABLED);
        assertThat(views.get(1).status()).isNull();
        assertThat(code(() -> lifecycle.tenantModules(UUID.randomUUID()))).isEqualTo("TENANT_NOT_FOUND");
        verify(locks, never()).lock(anyString());
    }
}
