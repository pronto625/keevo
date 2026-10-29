package com.keevo.shared.module.application;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.keevo.shared.module.ModuleCategory;
import com.keevo.shared.module.ModuleManifest;
import com.keevo.shared.module.TenantModuleStatus;
import com.keevo.shared.module.application.port.TenantModuleStatePort;
import com.keevo.shared.module.infrastructure.RequestScopedActiveModulesCache;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static com.keevo.shared.module.TestModuleCatalogs.ALL;
import static com.keevo.shared.module.TestModuleCatalogs.FREE;
import static com.keevo.shared.module.TestModuleCatalogs.loaded;
import static com.keevo.shared.module.TestModuleCatalogs.testCatalog;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Story 13.2 — effective state rule of {@link ModuleRegistryService} and its request-scoped cache, on a fake port
 * (the SQL side is covered by {@code ModuleRegistryPostgresIntegrationTest}).
 */
class ModuleRegistryServiceTest {

    private static final String TENANT = "kv_abc123";
    private static final String OTHER = "kv_zzz999";
    /** Fake port: per-schema rows (absent schema = unknown tenant) + a call counter. */
    private final Map<String, Map<String, TenantModuleStatus>> tenants = new HashMap<>();
    private final AtomicInteger queries = new AtomicInteger();
    private final TenantModuleStatePort port = schema -> {
        queries.incrementAndGet();
        return Optional.ofNullable(tenants.get(schema)).map(Map::copyOf);
    };

    private ModuleRegistryService registry;

    @BeforeEach
    void setUp() {
        tenants.put(TENANT, new HashMap<>());
        tenants.put(OTHER, new HashMap<>());
        registry = new ModuleRegistryService(testCatalog(), port, new RequestScopedActiveModulesCache());
    }

    @AfterEach
    void unbindRequest() {
        RequestContextHolder.resetRequestAttributes();
    }

    private void row(String moduleId, TenantModuleStatus status) {
        tenants.get(TENANT).put(moduleId, status);
    }

    private static void bindRequest() {
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(new MockHttpServletRequest()));
    }

    // ── installed / definition ──────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("installed() = the catalogue manifests; definition() finds installed ids only")
    void installedAndDefinition() {
        assertThat(registry.installed()).extracting(ModuleManifest::id).containsExactlyElementsOf(ALL); // sorted by id
        assertThat(registry.definition("pos")).map(ModuleManifest::id).contains("pos");
        assertThat(registry.definition("legacy")).isEmpty();
        assertThat(registry.definition(null)).isEmpty();
    }

    // ── effective state rule ────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("existing tenant without rows: every free module is active, the paid one is not")
    void noRowsAllFreeModulesActive() {
        assertThat(registry.activeModules(TENANT)).containsExactlyElementsOf(FREE);
    }

    @Test
    @DisplayName("a DISABLED row turns only that module off")
    void disabledRow() {
        row("pos", TenantModuleStatus.DISABLED);
        assertThat(registry.isActive(TENANT, "pos")).isFalse();
        assertThat(registry.activeModules(TENANT)).hasSize(FREE.size() - 1).doesNotContain("pos");
    }

    @Test
    @DisplayName("EXPIRED and AVAILABLE rows are inactive; TRIAL and ACTIVE rows are active")
    void statusDecidesWhenARowExists() {
        row("crm", TenantModuleStatus.EXPIRED);
        row("store", TenantModuleStatus.AVAILABLE);
        row("reporting", TenantModuleStatus.TRIAL);
        row("admin", TenantModuleStatus.ACTIVE);
        assertThat(registry.isActive(TENANT, "crm")).isFalse();
        assertThat(registry.isActive(TENANT, "store")).isFalse();
        assertThat(registry.isActive(TENANT, "reporting")).isTrue();
        assertThat(registry.isActive(TENANT, "admin")).isTrue();
    }

    @Test
    @DisplayName("core stays active whatever its row says")
    void coreAlwaysActive() {
        row("core", TenantModuleStatus.DISABLED);
        assertThat(registry.isActive(TENANT, "core")).isTrue();
    }

    @Test
    @DisplayName("an ACTIVE row of a module that is not installed (orphan) never activates it")
    void orphanRowIgnored() {
        row("legacy", TenantModuleStatus.ACTIVE);
        assertThat(registry.isActive(TENANT, "legacy")).isFalse();
        assertThat(registry.activeModules(TENANT)).doesNotContain("legacy");
        assertThat(registry.installed()).extracting(ModuleManifest::id).doesNotContain("legacy");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"ghost", "POS"})
    @DisplayName("an unknown module id is never active")
    void unknownModuleId(String moduleId) {
        assertThat(registry.isActive(TENANT, moduleId)).isFalse();
    }

    @Test
    @DisplayName("a paid module without a row is off; a TRIAL row turns it on")
    void paidModuleNeedsARow() {
        assertThat(registry.isActive(TENANT, "invoicing")).isFalse();
        row("invoicing", TenantModuleStatus.TRIAL);
        assertThat(registry.isActive(TENANT, "invoicing")).isTrue();
    }

    @Test
    @DisplayName("unknown tenant: only core, logged at DEBUG")
    void unknownTenantOnlyCore() {
        Logger logger = (Logger) LoggerFactory.getLogger(ModuleRegistryService.class);
        ListAppender<ILoggingEvent> logs = new ListAppender<>();
        logs.start();
        Level previous = logger.getLevel();
        logger.setLevel(Level.DEBUG);
        logger.addAppender(logs);
        try {
            assertThat(registry.activeModules("kv_nobody")).containsExactly("core");
            assertThat(registry.isActive("kv_nobody", "pos")).isFalse();
        } finally {
            logger.detachAppender(logs);
            logger.setLevel(previous);
        }
        assertThat(logs.list).anySatisfy(e -> {
            assertThat(e.getLevel()).isEqualTo(Level.DEBUG);
            assertThat(e.getFormattedMessage()).contains("only core is active").doesNotContain("kv_nobody");
        });
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"PUBLIC", "public ", "KV_1", "kv_ABC123", "kv_abc12", "kv_abc123; drop"})
    @DisplayName("an invalid schema name (anything but a tenant schema or exactly \"public\") throws before any query")
    void invalidSchema(String schema) {
        assertThatThrownBy(() -> registry.activeModules(schema)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> registry.isActive(schema, "pos")).isInstanceOf(IllegalArgumentException.class);
        assertThat(queries).hasValue(0);
    }

    @Test
    @DisplayName("the result is immutable, sorted, and contains(null) answers false")
    void immutableResult() {
        Set<String> active = registry.activeModules(TENANT);
        assertThatThrownBy(() -> active.add("ghost")).isInstanceOf(UnsupportedOperationException.class);
        assertThat(active).containsExactlyElementsOf(FREE);
        assertThat(active.contains(null)).isFalse();
        assertThat(registry.activeModules("kv_nobody").contains(null)).isFalse();
        assertThat(registry.activeModules("public").contains(null)).isFalse();
    }

    @Test
    @DisplayName("platform-admin session (public): core + platform modules, no query, never cached")
    void platformAdminSession() {
        row("pos", TenantModuleStatus.DISABLED);
        bindRequest();
        assertThat(registry.activeModules("public")).containsExactly("admin", "core");
        assertThat(registry.isActive("public", "admin")).isTrue();
        assertThat(registry.isActive("public", "pos")).isFalse();
        assertThat(registry.isActive("public", "invoicing")).isFalse();
        registry.evict("public"); // no-op
        assertThat(queries).hasValue(0);
    }

    // ── catalogue invariants ────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("ModuleCatalog(List) sorts by id and requires exactly one core")
    void catalogInvariants() {
        assertThat(testCatalog().manifests()).extracting(ModuleManifest::id).containsExactlyElementsOf(ALL);
        assertThatThrownBy(() -> new ModuleCatalog(List.of(loaded("pos", ModuleCategory.BUSINESS, true))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("core");
        assertThatThrownBy(() -> new ModuleCatalog(List.of(loaded("core", ModuleCategory.CORE, true),
                loaded("core", ModuleCategory.CORE, true))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Duplicate");
    }

    // ── cache ───────────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("within one request: 5 calls for one tenant = 1 query; another tenant = its own query")
    void oneQueryPerTenantPerRequest() {
        bindRequest();
        registry.activeModules(TENANT);
        registry.isActive(TENANT, "pos");
        registry.isActive(TENANT, "crm");
        registry.activeModules(TENANT);
        registry.isActive(TENANT, "ghost");
        assertThat(queries).hasValue(1);

        registry.isActive(OTHER, "pos");
        assertThat(queries).hasValue(2);
    }

    @Test
    @DisplayName("a new request reads the database again (no state carried across requests)")
    void newRequestReadsAgain() {
        bindRequest();
        assertThat(registry.isActive(TENANT, "pos")).isTrue();
        row("pos", TenantModuleStatus.DISABLED);
        assertThat(registry.isActive(TENANT, "pos")).isTrue(); // same request: cached
        bindRequest();
        assertThat(registry.isActive(TENANT, "pos")).isFalse();
        assertThat(queries).hasValue(2);
    }

    @Test
    @DisplayName("an unknown tenant is cached for the request too")
    void unknownTenantCached() {
        bindRequest();
        registry.activeModules("kv_nobody");
        registry.isActive("kv_nobody", "core");
        assertThat(queries).hasValue(1);
    }

    @Test
    @DisplayName("outside a request (scheduler thread): every call reads the database")
    void noRequestNoCache() {
        RequestContextHolder.resetRequestAttributes();
        registry.activeModules(TENANT);
        registry.isActive(TENANT, "pos");
        row("pos", TenantModuleStatus.DISABLED);
        assertThat(registry.isActive(TENANT, "pos")).isFalse();
        assertThat(queries).hasValue(3);
    }

    @Test
    @DisplayName("evict() drops the tenant's cached state within the request; safe without a request")
    void evictWithinRequest() {
        registry.evict(TENANT); // no request bound: no-op, no exception
        registry.evict("KV_1"); // invalid: no-op
        bindRequest();
        assertThat(registry.isActive(TENANT, "pos")).isTrue();
        registry.isActive(OTHER, "pos");
        row("pos", TenantModuleStatus.DISABLED);
        registry.evict(TENANT);
        assertThat(registry.isActive(TENANT, "pos")).isFalse();
        registry.isActive(OTHER, "pos"); // other tenant still cached
        assertThat(queries).hasValue(3);
    }

    @Test
    @DisplayName("attributes of a completed request still bound to the thread: a miss, never an exception")
    void completedRequestIsAMiss() {
        ServletRequestAttributes attributes = new ServletRequestAttributes(new MockHttpServletRequest());
        RequestContextHolder.setRequestAttributes(attributes);
        registry.isActive(TENANT, "pos");
        attributes.requestCompleted();
        assertThat(registry.isActive(TENANT, "pos")).isTrue();
        assertThat(registry.isActive(TENANT, "pos")).isTrue();
        registry.evict(TENANT);
        assertThat(queries).hasValue(3);
    }

    // ── isKnownTenant (Story 12.7) ──────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("isKnownTenant: a registered tenant is known even with every non-core module off")
    void knownTenantWithModulesOff() {
        for (String id : FREE) {
            if (!"core".equals(id)) {
                row(id, TenantModuleStatus.DISABLED);
            }
        }
        assertThat(registry.activeModules(TENANT)).containsExactly("core");
        assertThat(registry.isKnownTenant(TENANT)).isTrue();
    }

    @Test
    @DisplayName("isKnownTenant: an unknown tenant is not; the platform schema is not; invalid strings throw")
    void unknownTenantIsNotKnown() {
        assertThat(registry.isKnownTenant("kv_nobody")).isFalse();
        assertThat(registry.isKnownTenant("public")).isFalse();
        assertThatThrownBy(() -> registry.isKnownTenant("KV_1")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("isKnownTenant + activeModules in one request: one query")
    void knownTenantSharesTheRequestCache() {
        bindRequest();
        registry.isKnownTenant(TENANT);
        registry.activeModules(TENANT);
        registry.isKnownTenant(TENANT);
        assertThat(queries).hasValue(1);
    }
}
