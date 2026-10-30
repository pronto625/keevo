package com.keevo.sync.sync.application.strategy;

import com.keevo.shared.application.port.sync.SyncOperation;
import com.keevo.shared.application.port.sync.SyncOperationResult;
import com.keevo.shared.application.port.sync.SyncOperationStatus;
import com.keevo.sync.sync.domain.model.*;
import com.keevo.sync.sync.domain.port.out.EntityLastModifiedRepository;
import com.keevo.sync.sync.domain.port.out.EntityLastModifiedRepository.LwwEntity;
import com.keevo.sync.sync.domain.port.out.SyncOperationsLogRepository;
import com.keevo.sync.sync.domain.port.out.UserSyncStateRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("LastWriteWinsStrategy — last-write-wins by client timestamp")
class LastWriteWinsStrategyTest {

    private static final String TENANT = "kv_abc123";
    private static final String DEVICE = "device-A";
    private static final UUID ID = UUID.fromString("11111111-2222-3333-4444-555555555555");
    /** Server-side last modification of the entity (e.g. the owner's REST PATCH at 02:48). */
    private static final Instant T2 = Instant.parse("2026-10-14T02:48:00Z");
    private static final Instant T1 = Instant.parse("2026-10-14T02:44:00Z");
    private static final Instant T3 = Instant.parse("2026-10-14T02:49:00Z");

    @Mock private SyncOperationsLogRepository logRepository;
    @Mock private EntityLastModifiedRepository entityLastModified;
    @Mock private UserSyncStateRepository userSyncStateRepository;
    @Mock private ApplicationEventPublisher eventPublisher;

    private LastWriteWinsStrategy strategy;

    @BeforeEach
    void setUp() {
        strategy = new LastWriteWinsStrategy(logRepository, entityLastModified, userSyncStateRepository,
                eventPublisher);
        when(logRepository.findPreviousAppliedByEntityId(anyString(), anyString())).thenReturn(Optional.empty());
        when(userSyncStateRepository.findByDeviceId(anyString())).thenReturn(Optional.empty());
        authenticate("ROLE_OWNER");
    }

    @AfterEach
    void clearAuth() {
        SecurityContextHolder.clearContext();
    }

    private static void authenticate(String role) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                UUID.randomUUID(), null, List.of(new SimpleGrantedAuthority(role))));
    }

    private static SyncOperation op(String type, String key, Instant ts) {
        Map<String, Object> payload = new HashMap<>();
        payload.put(key, ID.toString());
        payload.put("name", "Nouveau nom");
        return new SyncOperation("op-new", type, ID.toString(), payload, ts);
    }

    private static SyncOperation productUpdate(Instant ts) {
        return op("UPDATE_PRODUCT", "productId", ts);
    }

    private void entityUpdatedAt(LwwEntity entity, Instant at) {
        when(entityLastModified.findUpdatedAt(entity, ID)).thenReturn(Optional.of(at));
    }

    private void previousSyncWrite(String deviceId, Instant clientTs, Instant processedAt) {
        when(logRepository.findPreviousAppliedByEntityId(ID.toString(), "op-new")).thenReturn(Optional.of(
                new SyncOperationsLogEntry("op-prev", "UPDATE_PRODUCT", ID.toString(), SyncOperationStatus.APPLIED,
                        null, processedAt, clientTs, deviceId)));
    }

    private void lastPull(Instant at) {
        when(userSyncStateRepository.findByDeviceId(DEVICE)).thenReturn(Optional.of(
                new UserSyncState(DEVICE, UUID.randomUUID(), TENANT, null, at, at)));
    }

    // ── Superseded (incoming older than the current state) ────────────────────

    @ParameterizedTest(name = "{0} older than the entity → superseded")
    @CsvSource({
            "UPDATE_PRODUCT, productId, PRODUCT",
            "ARCHIVE_PRODUCT, productId, PRODUCT",
            "UNARCHIVE_PRODUCT, productId, PRODUCT",
            "UPDATE_CLIENT, clientId, CLIENT",
            "ARCHIVE_CLIENT, clientId, CLIENT",
            "UNARCHIVE_CLIENT, clientId, CLIENT",
            "UPDATE_SUPPLIER, supplierId, SUPPLIER",
            "ARCHIVE_SUPPLIER, supplierId, SUPPLIER",
            "UNARCHIVE_SUPPLIER, supplierId, SUPPLIER",
            "UPDATE_STORE, storeId, STORE",
            "DEACTIVATE_STORE, storeId, STORE",
            "RENAME_CATEGORY, categoryId, CATEGORY",
            "TOGGLE_CATEGORY, categoryId, CATEGORY"})
    void olderThanServerChange_superseded(String type, String key, LwwEntity entity) {
        entityUpdatedAt(entity, T2);

        PreApplyCheck check = strategy.beforeApply(op(type, key, T1), DEVICE, TENANT);

        assertThat(check.isSuperseded()).isTrue();
        assertThat(check.superseded().conflictType()).isEqualTo(LastWriteWinsStrategy.SUPERSEDED);
        assertThat(check.superseded().strategy()).isEqualTo("LAST_WRITE_WINS");
        assertThat(check.superseded().conflictData())
                .containsEntry("entityType", entity.name())
                .containsEntry("entityId", ID.toString())
                .containsEntry("incomingTimestamp", T1.toString())
                .containsEntry("keptTimestamp", T2.toString());
    }

    @ParameterizedTest(name = "{0} newer than the entity → applied")
    @CsvSource({
            "UPDATE_PRODUCT, productId, PRODUCT",
            "UPDATE_CLIENT, clientId, CLIENT",
            "UPDATE_SUPPLIER, supplierId, SUPPLIER",
            "UPDATE_STORE, storeId, STORE",
            "RENAME_CATEGORY, categoryId, CATEGORY"})
    void newerThanServerChange_applied(String type, String key, LwwEntity entity) {
        entityUpdatedAt(entity, T2);
        lastPull(T3); // the device had seen T2: not a concurrent overwrite

        PreApplyCheck check = strategy.beforeApply(op(type, key, T3), DEVICE, TENANT);

        assertThat(check).isEqualTo(PreApplyCheck.NONE);
    }

    @Test
    void equalTimestamp_applied() {
        entityUpdatedAt(LwwEntity.PRODUCT, T2);
        lastPull(T3);

        assertThat(strategy.beforeApply(productUpdate(T2), DEVICE, TENANT).isSuperseded()).isFalse();
    }

    @Test
    void nullClientTimestamp_applied() {
        entityUpdatedAt(LwwEntity.PRODUCT, T2);
        lastPull(T3);

        assertThat(strategy.beforeApply(productUpdate(null), DEVICE, TENANT).isSuperseded()).isFalse();
    }

    @Test
    void unknownEntity_noDecision() {
        when(entityLastModified.findUpdatedAt(LwwEntity.PRODUCT, ID)).thenReturn(Optional.empty());

        assertThat(strategy.beforeApply(productUpdate(T1), DEVICE, TENANT)).isEqualTo(PreApplyCheck.NONE);
    }

    @Test
    void creationAndStockOperations_notConcerned() {
        var create = new SyncOperation("op-c", "CREATE_PRODUCT", ID.toString(),
                Map.of("id", ID.toString(), "name", "Riz"), T1);
        var sale = new SyncOperation("op-s", "CREATE_SALE", ID.toString(), Map.of(), T1);

        assertThat(strategy.beforeApply(create, DEVICE, TENANT)).isEqualTo(PreApplyCheck.NONE);
        assertThat(strategy.beforeApply(sale, DEVICE, TENANT)).isEqualTo(PreApplyCheck.NONE);
        verifyNoInteractions(entityLastModified);
    }

    @Test
    void ownerOnlyOperation_fromEmployee_leftToTheHandler() {
        authenticate("ROLE_EMPLOYEE");
        entityUpdatedAt(LwwEntity.STORE, T2);

        assertThat(strategy.beforeApply(op("UPDATE_STORE", "storeId", T1), DEVICE, TENANT))
                .isEqualTo(PreApplyCheck.NONE);
    }

    @Test
    void invalidId_noDecision() {
        var op = new SyncOperation("op-new", "UPDATE_PRODUCT", null, Map.of("productId", "not-a-uuid"), T1);

        assertThat(strategy.beforeApply(op, DEVICE, TENANT)).isEqualTo(PreApplyCheck.NONE);
    }

    // ── Current state written by a sync operation ────────────────────────────

    @Test
    @DisplayName("same device, sequential edits pushed together: compared device time to device time")
    void sameDeviceSequentialEdits_notSupersededNorConflict() {
        // Edit 1 (device time 02:40) applied at 02:50 server time; edit 2 (02:44) in the same push.
        Instant applied = Instant.parse("2026-10-14T02:50:00Z");
        entityUpdatedAt(LwwEntity.PRODUCT, applied);
        previousSyncWrite(DEVICE, Instant.parse("2026-10-14T02:40:00Z"), applied.plusMillis(3));

        PreApplyCheck check = strategy.beforeApply(productUpdate(T1), DEVICE, TENANT);

        assertThat(check).isEqualTo(PreApplyCheck.NONE);
    }

    @Test
    void otherDeviceNewerEdit_supersedesOlderIncoming() {
        Instant applied = Instant.parse("2026-10-14T02:50:00Z");
        entityUpdatedAt(LwwEntity.PRODUCT, applied);
        previousSyncWrite("device-B", T2, applied);

        PreApplyCheck check = strategy.beforeApply(productUpdate(T1), DEVICE, TENANT);

        assertThat(check.isSuperseded()).isTrue();
        assertThat(check.superseded().conflictData()).containsEntry("keptTimestamp", T2.toString());
    }

    @Test
    @DisplayName("another device's edit the pushing device had not pulled → overwrite conflict")
    void otherDeviceOverwritten_conflict() {
        Instant applied = Instant.parse("2026-10-14T02:50:00Z");
        entityUpdatedAt(LwwEntity.PRODUCT, applied);
        previousSyncWrite("device-B", T1, applied);
        lastPull(Instant.parse("2026-10-14T02:30:00Z"));

        PreApplyCheck check = strategy.beforeApply(productUpdate(T2), DEVICE, TENANT);

        assertThat(check.isSuperseded()).isFalse();
        assertThat(check.overwrite()).isNotNull();
        assertThat(check.overwrite().conflictType()).isEqualTo("LAST_WRITE_WINS");
        assertThat(check.overwrite().conflictData())
                .containsEntry("overwrittenTimestamp", T1.toString())
                .containsEntry("winnerTimestamp", T2.toString());
    }

    @Test
    @DisplayName("another device's edit already pulled by the pushing device → sequential, no conflict")
    void otherDeviceEditAlreadyPulled_noConflict() {
        Instant applied = Instant.parse("2026-10-14T02:50:00Z");
        entityUpdatedAt(LwwEntity.PRODUCT, applied);
        previousSyncWrite("device-B", T1, applied);
        lastPull(applied.plusSeconds(60));

        assertThat(strategy.beforeApply(productUpdate(T3.plusSeconds(600)), DEVICE, TENANT))
                .isEqualTo(PreApplyCheck.NONE);
    }

    @Test
    void previousWriteFromUnknownDevice_noConflict() {
        Instant applied = Instant.parse("2026-10-14T02:50:00Z");
        entityUpdatedAt(LwwEntity.PRODUCT, applied);
        previousSyncWrite(null, T1, applied);

        assertThat(strategy.beforeApply(productUpdate(T2), DEVICE, TENANT)).isEqualTo(PreApplyCheck.NONE);
    }

    @Test
    @DisplayName("a later server change than the last sync write: the server change is the reference")
    void serverChangeAfterSyncWrite_serverIsReference() {
        Instant syncApplied = Instant.parse("2026-10-14T02:30:00Z");
        previousSyncWrite(DEVICE, Instant.parse("2026-10-14T02:20:00Z"), syncApplied);
        entityUpdatedAt(LwwEntity.PRODUCT, T2); // REST edit after the sync write

        assertThat(strategy.beforeApply(productUpdate(T1), DEVICE, TENANT).isSuperseded()).isTrue();
    }

    @Test
    @DisplayName("server change not yet pulled by the device, incoming newer → applied and logged as overwrite")
    void newerThanUnseenServerChange_overwriteConflict() {
        entityUpdatedAt(LwwEntity.PRODUCT, T2);
        lastPull(Instant.parse("2026-10-14T02:30:00Z"));

        PreApplyCheck check = strategy.beforeApply(productUpdate(T3), DEVICE, TENANT);

        assertThat(check.isSuperseded()).isFalse();
        assertThat(check.overwrite()).isNotNull();
    }

    @Test
    void lastPullUnknown_noConflict() {
        Instant applied = Instant.parse("2026-10-14T02:50:00Z");
        entityUpdatedAt(LwwEntity.PRODUCT, applied);
        previousSyncWrite("device-B", T1, applied);

        assertThat(strategy.beforeApply(productUpdate(T2), DEVICE, TENANT)).isEqualTo(PreApplyCheck.NONE);
    }

    // ── evaluate (after the handler) ─────────────────────────────────────────

    @Test
    void evaluate_overwriteApplied_returnsItAndPublishesEvent() {
        var overwrite = new ConflictResult("LAST_WRITE_WINS", "LAST_WRITE_WINS", Map.of(
                "entityType", "PRODUCT", "entityId", ID.toString(), "overwrittenTimestamp", T1.toString()));
        var applied = new SyncOperationResult("op-new", SyncOperationStatus.APPLIED, ID.toString(), null);

        ConflictResult result = strategy.evaluate(productUpdate(T2), applied, UUID.randomUUID(), TENANT,
                PreApplyCheck.overwrite(overwrite));

        assertThat(result).isSameAs(overwrite);
        verify(eventPublisher).publishEvent(any(SyncOverwrittenEvent.class));
    }

    @Test
    void evaluate_noConcurrentChange_noConflictNoEvent() {
        var applied = new SyncOperationResult("op-new", SyncOperationStatus.APPLIED, ID.toString(), null);

        assertThat(strategy.evaluate(productUpdate(T2), applied, UUID.randomUUID(), TENANT, PreApplyCheck.NONE))
                .isNull();
        assertThat(strategy.evaluate(productUpdate(T2), applied, UUID.randomUUID(), TENANT)).isNull();
        verifyNoInteractions(eventPublisher);
    }

    @Test
    void evaluate_handlerRejected_noConflict() {
        var overwrite = new ConflictResult("LAST_WRITE_WINS", "LAST_WRITE_WINS", Map.of("entityType", "PRODUCT"));
        var rejected = new SyncOperationResult("op-new", SyncOperationStatus.REJECTED, null, "ERROR");

        assertThat(strategy.evaluate(productUpdate(T2), rejected, UUID.randomUUID(), TENANT,
                PreApplyCheck.overwrite(overwrite))).isNull();
        verifyNoInteractions(eventPublisher);
    }
}
