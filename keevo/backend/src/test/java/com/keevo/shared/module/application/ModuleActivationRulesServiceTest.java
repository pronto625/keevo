package com.keevo.shared.module.application;

import com.keevo.identity.api.SaleFlow;
import com.keevo.identity.api.TenantSaleFlowQueryPort;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import com.keevo.shared.module.ModuleActivationRulesAdmin;
import com.keevo.shared.module.application.port.ModuleActivationRuleStore;
import com.keevo.shared.module.infrastructure.RequestScopedActiveModulesCache;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import static com.keevo.shared.module.application.OwnerModuleActivationTestSupport.SCHEMA;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Story 19.13 — the initial activation rules: what is applied for a tenant (flow + sector read through identity.api),
 * and the super-admin validation (unknown / non-toggleable module, unknown flow / sector, dependency closure, default
 * sets not deletable). The SQL resolution itself is covered by the full-context test.
 */
@DisplayName("19.13 — ModuleActivationRulesService (apply, admin validation)")
class ModuleActivationRulesServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-05T10:00:00Z");

    /** Fake store: key "FLOW|sector" → modules; applyInitial records its arguments. */
    private final Map<String, List<String>> sets = new LinkedHashMap<>();
    private final List<String> applied = new ArrayList<>();
    private Optional<TenantSaleFlowQueryPort.TenantSaleFlowView> profile = Optional.empty();
    private ModuleActivationRulesService service;

    private final ModuleActivationRuleStore store = new ModuleActivationRuleStore() {
        @Override
        public List<StoredRuleSet> findAll() {
            List<StoredRuleSet> all = new ArrayList<>();
            sets.forEach((key, modules) -> {
                String[] k = key.split("\\|", -1);
                all.add(new StoredRuleSet(UUID.nameUUIDFromBytes(key.getBytes()), k[0], k[1].isEmpty() ? null : k[1],
                        modules, NOW));
            });
            return all;
        }

        @Override
        public boolean save(String saleFlow, String sectorType, List<String> modules, Instant at) {
            return sets.put(saleFlow + "|" + Objects.toString(sectorType, ""), modules) == null;
        }

        @Override
        public boolean delete(String saleFlow, String sectorType) {
            return sets.remove(saleFlow + "|" + Objects.toString(sectorType, "")) != null;
        }

        @Override
        public int applyInitial(String tenantSchema, String saleFlow, String sectorType, Instant at) {
            applied.add(tenantSchema + " " + saleFlow + " " + sectorType);
            return 4;
        }
    };

    @BeforeEach
    void setUp() {
        ModuleCatalog catalog = OwnerModuleActivationTestSupport.catalog();
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        ModuleRegistryService registry = new ModuleRegistryService(catalog,
                new OwnerModuleActivationTestSupport().statePort(), new RequestScopedActiveModulesCache(), clock);
        TenantSaleFlowQueryPort saleFlows = schema -> profile;
        service = new ModuleActivationRulesService(catalog, store, saleFlows, registry, new NoTx(), clock);
        sets.put("POS|", List.of());
        sets.put("DOCUMENT|", List.of("counting", "inventory", "multi-store", "pos"));
    }

    private static DomainException invalid(Runnable call) {
        try {
            call.run();
        } catch (DomainException e) {
            return e;
        }
        throw new AssertionError("expected a refusal");
    }

    @Test
    @DisplayName("apply: the tenant's flow and sector; no preferences → POS without sector")
    void apply() {
        profile = Optional.of(new TenantSaleFlowQueryPort.TenantSaleFlowView(SaleFlow.DOCUMENT, "PHARMACY"));
        assertThat(service.applyInitialRules(SCHEMA)).isEqualTo(4);
        profile = Optional.empty();
        service.applyInitialRules(SCHEMA);
        assertThat(applied).containsExactly(SCHEMA + " DOCUMENT PHARMACY", SCHEMA + " POS null");
    }

    @Test
    @DisplayName("save: a sector set, sorted, created then replaced; list shows display names and the default flag")
    void save() {
        ModuleActivationRulesAdmin.SaveResult created = service.save("DOCUMENT", "PHARMACY",
                List.of("pos", "counting", "multi-store", "inventory", "invoicing"), UUID.randomUUID());
        assertThat(created.created()).isTrue();
        assertThat(created.ruleSet().sectorType()).isEqualTo("PHARMACY");
        assertThat(created.ruleSet().defaultSet()).isFalse();
        assertThat(created.ruleSet().disabledModules()).extracting(r -> r.moduleId())
                .containsExactly("counting", "inventory", "invoicing", "multi-store", "pos");
        assertThat(created.ruleSet().disabledModules()).extracting(r -> r.displayName())
                .contains("Facturation", "Point de vente");
        assertThat(service.save("DOCUMENT", "PHARMACY", List.of(), null).created()).isFalse();
        assertThat(service.save("POS", null, List.of("invoicing"), null).ruleSet().defaultSet()).isTrue();
        assertThat(service.list()).hasSize(3);
    }

    @Test
    @DisplayName("save refused (422 MODULE_ACTIVATION_RULE_INVALID, nothing saved): flow, sector, module, "
            + "non-toggleable, duplicate, not dependency-closed, missing list, blank sector « Secteur inconnu »")
    void saveRefused() {
        Map<String, Runnable> cases = new LinkedHashMap<>();
        cases.put("Règle non enregistrée : parcours de vente inconnu : choisissez Caisse ou Documents",
                () -> service.save("SHOP", null, List.of(), null));
        cases.put("Règle non enregistrée : secteur d'activité inconnu",
                () -> service.save("POS", "BAKERY", List.of(), null));
        cases.put("Règle non enregistrée : un des modules demandés n'existe pas",
                () -> service.save("POS", null, List.of("foo"), null));
        cases.put("Règle non enregistrée : « store » ne peut pas être désactivé",
                () -> service.save("POS", null, List.of("store"), null));
        cases.put("Règle non enregistrée : « Facturation » figure plusieurs fois dans la liste",
                () -> service.save("POS", null, List.of("invoicing", "invoicing"), null));
        cases.put("Règle non enregistrée : « inventory » est requis par Inventaire assisté, Multi-boutiques et "
                + "transferts : désactivez-les aussi", () -> service.save("POS", null, List.of("inventory", "pos"), null));
        cases.put("Règle non enregistrée : indiquez les modules désactivés au départ (liste vide si aucun)",
                () -> service.save("POS", null, null, null));
        cases.put("Secteur inconnu", () -> service.save("POS", "", List.of(), null));
        cases.put("Secteur inconnu ", () -> service.save("POS", "   ", List.of(), null));
        cases.forEach((message, call) -> {
            DomainException e = invalid(call);
            assertThat(e.getErrorCode()).isEqualTo(ErrorCode.MODULE_ACTIVATION_RULE_INVALID);
            assertThat(e.getUserMessage()).isEqualTo(message.strip());
        });
        assertThat(sets).hasSize(2);
    }

    @Test
    @DisplayName("delete: a sector set; the default set → 409 INVALID_STATE; blank sector → 422; absent → 404")
    void delete() {
        service.save("DOCUMENT", "PHARMACY", List.of(), null);
        service.delete("DOCUMENT", "PHARMACY", null);
        assertThat(sets).doesNotContainKey("DOCUMENT|PHARMACY");
        assertThat(invalid(() -> service.delete("DOCUMENT", null, null)).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_STATE);
        assertThat(invalid(() -> service.delete("DOCUMENT", "", null)).getErrorCode())
                .isEqualTo(ErrorCode.MODULE_ACTIVATION_RULE_INVALID);
        assertThat(invalid(() -> service.delete("DOCUMENT", "PHARMACY", null)).getErrorCode())
                .isEqualTo(ErrorCode.NOT_FOUND);
        assertThat(invalid(() -> service.delete("SHOP", "PHARMACY", null)).getErrorCode())
                .isEqualTo(ErrorCode.MODULE_ACTIVATION_RULE_INVALID);
        assertThatThrownBy(() -> service.delete("DOCUMENT", "BAKERY", null)).isInstanceOf(DomainException.class);
    }

    /** A transaction manager that only runs the callback (the fakes are not transactional). */
    private static final class NoTx implements PlatformTransactionManager {
        @Override
        public TransactionStatus getTransaction(TransactionDefinition definition) {
            return new SimpleTransactionStatus();
        }

        @Override
        public void commit(TransactionStatus status) {
        }

        @Override
        public void rollback(TransactionStatus status) {
        }
    }
}
