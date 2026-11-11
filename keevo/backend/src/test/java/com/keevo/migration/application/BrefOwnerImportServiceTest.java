package com.keevo.migration.application;

import com.keevo.identity.api.LegacyIdentityImportPort;
import com.keevo.identity.api.TenantQueryPort;
import com.keevo.migration.domain.model.BatchStatus;
import com.keevo.migration.domain.model.BrefAccount;
import com.keevo.migration.domain.model.BrefShop;
import com.keevo.migration.domain.model.BrefSnapshot;
import com.keevo.migration.domain.model.MigrationBatch;
import com.keevo.migration.domain.model.SourceKind;
import com.keevo.migration.domain.port.out.BrefSourcePort;
import com.keevo.migration.domain.port.out.MigrationRecordStore;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.store.api.StoreCommandPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Story 24.2 — the orchestration: refusals before any batch, blocked run, failed step (no step after it). */
@DisplayName("24.2 — BrefOwnerImportService (orchestration)")
class BrefOwnerImportServiceTest {

    private static final Instant T = Instant.parse("2026-03-01T08:00:00Z");
    private final UUID ownerId = UUID.randomUUID();
    private final UUID actor = UUID.randomUUID();
    private final UUID batchId = UUID.randomUUID();

    private final BrefSourcePort database = mock(BrefSourcePort.class);
    private final BrefSourcePort export = mock(BrefSourcePort.class);
    private final MigrationRecordStore store = mock(MigrationRecordStore.class);
    private final LegacyIdentityImportPort identity = mock(LegacyIdentityImportPort.class);
    private final TenantQueryPort tenants = mock(TenantQueryPort.class);
    private final StoreCommandPort storeRules = mock(StoreCommandPort.class);
    private final List<String> ran = new ArrayList<>();
    private BrefOwnerImportService service;

    private ImportStep step(String name, RuntimeException failure) {
        return new ImportStep() {
            @Override
            public void run(ImportRun run) {
                ran.add(name);
                if (failure != null) {
                    throw failure;
                }
            }

            @Override
            public String label() {
                return name;
            }

            @Override
            public java.util.Set<String> targetTables() {
                return java.util.Set.of("stores");
            }
        };
    }

    private BrefSnapshot snapshot(String ownerPhone) {
        BrefAccount owner = new BrefAccount(ownerId, "owner", null, false, ownerPhone, true, null, null, "A", "B",
                null, true, false, "email", null, T, T, null);
        BrefShop shop = new BrefShop(UUID.randomUUID(), ownerId, "Boutique", "Adresse", "food", null, null, null,
                null, null, "CM", true, T, T, null, null);
        return new BrefSnapshot(owner, List.of(shop), List.of(), SourceKind.DATABASE);
    }

    @BeforeEach
    void setUp() {
        when(database.kind()).thenReturn(SourceKind.DATABASE);
        when(export.kind()).thenReturn(SourceKind.EXPORT);
        when(database.available()).thenReturn(true);
        when(export.available()).thenReturn(false);
        when(store.findTargets(eq(ownerId), any())).thenAnswer(i -> new java.util.HashMap<>());
        when(store.findBatch(batchId)).thenReturn(Optional.of(new MigrationBatch(batchId, ownerId,
                SourceKind.DATABASE, BatchStatus.COMPLETED, null, actor, T, T, Map.of(), List.of(), List.of(), null)));
        when(identity.findConflicts(any())).thenReturn(Map.of());
        service = new BrefOwnerImportService(List.of(database, export), store, identity, tenants, storeRules,
                List.of(step("un", null), step("deux", new IllegalStateException("+237600000001 duplicate")),
                        step("trois", null)));
    }

    private String refusal(String source) {
        return catchThrowableOfType(() -> service.importOwner(ownerId, source, actor), DomainException.class)
                .getDomainCode();
    }

    @Test
    @DisplayName("unknown source 422, unavailable source 503, concurrent run 409 — no batch, no step")
    void refusals() {
        assertThat(refusal("FTP")).isEqualTo("VALIDATION_FAILED");
        assertThat(refusal(null)).isEqualTo("VALIDATION_FAILED");
        assertThat(refusal("EXPORT")).isEqualTo("MIGRATION_SOURCE_UNAVAILABLE");
        when(database.load(ownerId)).thenReturn(snapshot("+237600000001"));
        when(store.startBatch(eq(ownerId), eq(SourceKind.DATABASE), eq(actor), any())).thenReturn(Optional.empty());
        assertThat(refusal("DATABASE")).isEqualTo("MIGRATION_ALREADY_RUNNING");
        assertThat(ran).isEmpty();
    }

    @Test
    @DisplayName("a blocking case: BLOCKED with the cases, no step run")
    void blocked() {
        when(database.load(ownerId)).thenReturn(snapshot("12"));
        when(store.startBatch(eq(ownerId), eq(SourceKind.DATABASE), eq(actor), any())).thenReturn(Optional.of(batchId));
        service.importOwner(ownerId, "DATABASE", actor);
        verify(store).finishBatch(eq(batchId), eq(BatchStatus.BLOCKED), isNull(), anyMap(),
                org.mockito.ArgumentMatchers.argThat(cases -> cases.size() == 2
                        && cases.get(0).code().equals("PHONE_INVALID")
                        && cases.get(1).code().equals("NO_IDENTIFIER")), anyList(), isNull());
        assertThat(ran).isEmpty();
    }

    @Test
    @DisplayName("records of a tenant that no longer exists (rolled back): BLOCKED TENANT_GONE, no step; only the "
            + "steps' record tables are loaded")
    void tenantGone() {
        when(database.load(ownerId)).thenReturn(snapshot("+237600000001"));
        when(store.startBatch(eq(ownerId), eq(SourceKind.DATABASE), eq(actor), any())).thenReturn(Optional.of(batchId));
        UUID tenantId = UUID.randomUUID();
        java.util.Map<com.keevo.migration.domain.model.MigrationRecord.Key, UUID> records = new java.util.HashMap<>();
        records.put(new com.keevo.migration.domain.model.MigrationRecord.Key("user", ownerId, "tenants"), tenantId);
        when(store.findTargets(eq(ownerId), any())).thenReturn(records);
        when(tenants.findById(tenantId)).thenReturn(Optional.of(new com.keevo.identity.api.TenantView(tenantId,
                "KV-ABC123", "kv_abc123", "X", com.keevo.identity.api.TenantStatus.DELETED,
                com.keevo.identity.api.PlanType.FREE, T, null)));
        service.importOwner(ownerId, "DATABASE", actor);
        verify(store).findTargets(ownerId, List.of("stores", "tenants", "users"));
        verify(store).finishBatch(eq(batchId), eq(BatchStatus.BLOCKED), isNull(), anyMap(),
                org.mockito.ArgumentMatchers.argThat(cases -> cases.size() == 1
                        && cases.get(0).code().equals("TENANT_GONE")), anyList(), isNull());
        assertThat(ran).isEmpty();
    }

    @Test
    @DisplayName("a failing step: FAILED with a French label naming the step, the next steps not run")
    void failedStep() {
        when(database.load(ownerId)).thenReturn(snapshot("+237600000001"));
        when(store.startBatch(eq(ownerId), eq(SourceKind.DATABASE), eq(actor), any())).thenReturn(Optional.of(batchId));
        MigrationBatch batch = service.importOwner(ownerId, "DATABASE", actor);
        assertThat(batch.id()).isEqualTo(batchId);
        assertThat(ran).containsExactly("un", "deux");
        verify(store).finishBatch(eq(batchId), eq(BatchStatus.FAILED), isNull(), anyMap(), anyList(), anyList(),
                startsWith("L'import s'est arrêté à l'étape « deux »"));
        verify(store, never()).finishBatch(eq(batchId), eq(BatchStatus.COMPLETED), any(), anyMap(), anyList(),
                anyList(), any());
    }
}
