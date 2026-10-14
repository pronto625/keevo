package com.keevo.sync.sync.application.service;

import com.keevo.catalog.product.application.usecase.CreateProductUseCase;
import com.keevo.catalog.product.application.usecase.CreateProductUseCase.CreateProductDto;
import com.keevo.catalog.product.application.usecase.UpdateProductUseCase;
import com.keevo.catalog.product.application.usecase.UpdateProductUseCase.UpdateProductDto;
import com.keevo.identity.auth.application.service.TenantFactory;
import com.keevo.shared.infrastructure.persistence.FullContextIntegrationTest;
import com.keevo.shared.infrastructure.persistence.TenantContext;
import com.keevo.sync.sync.domain.model.SyncBatchResult;
import com.keevo.sync.sync.domain.model.SyncOperation;
import com.keevo.sync.sync.domain.model.SyncOperationResult;
import com.keevo.sync.sync.domain.model.SyncOperationStatus;
import com.keevo.sync.sync.domain.port.in.SyncUseCase;
import com.keevo.sync.sync.domain.port.in.SyncUseCase.PushBatchCommand;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Last-write-wins by client timestamp on a real PostgreSQL with the production beans.
 *
 * <p>Device scenario: the phone edits a product price offline (3600, device time T1); meanwhile the
 * owner edits it on the server (3700, REST, T2 &gt; T1); the phone comes back online and pushes:
 * the server keeps 3700. An offline edit made after T2 is applied.
 */
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DisplayName("Last-write-wins by client timestamp (PostgreSQL)")
class LastWriteWinsPostgresIntegrationTest extends FullContextIntegrationTest {

    @Autowired TenantFactory tenantFactory;
    @Autowired TransactionTemplate tx;
    @Autowired JdbcTemplate jdbc;
    @Autowired SyncUseCase syncUseCase;
    @Autowired CreateProductUseCase createProduct;
    @Autowired UpdateProductUseCase updateProduct;

    private final UUID actorId = UUID.randomUUID();
    private String schema;

    @BeforeEach
    void setUp() {
        schema = tx.execute(s -> tenantFactory.create()).getSchemaName();
        TenantContext.setCurrentTenant(schema);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                actorId, null, List.of(new SimpleGrantedAuthority("ROLE_OWNER"))));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("offline edit older than the server edit is kept out; a newer one is applied")
    void deviceScenario_olderOfflineEditSuperseded_newerApplied() {
        UUID productId = newProduct("Savon LWW", 3500);

        // Owner's REST edit (T2) while the phone is offline.
        updateProduct.execute(new UpdateProductDto(productId, null, null, null, null, 3700,
                null, null, null, actorId, "OWNER"));
        Instant t2 = updatedAt(productId);

        // Phone back online: its price edit was made 4 minutes before T2 (device clock).
        SyncOperationResult stale = push("device-A",
                updateOp(productId, 3600, t2.minus(4, ChronoUnit.MINUTES))).results().get(0);

        assertThat(stale.status()).isEqualTo(SyncOperationStatus.APPLIED); // acknowledged, not retried
        assertThat(stale.reason()).isEqualTo("LWW_SUPERSEDED");
        assertThat(price(productId)).isEqualTo(3700);
        assertThat(updatedAt(productId)).isEqualTo(t2); // untouched
        assertThat(conflictTypes(stale.operationId())).containsExactly("LAST_WRITE_SUPERSEDED");

        // An edit made after T2 wins.
        SyncOperationResult fresh = push("device-A",
                updateOp(productId, 3800, t2.plus(1, ChronoUnit.MINUTES))).results().get(0);

        assertThat(fresh.status()).isEqualTo(SyncOperationStatus.APPLIED);
        assertThat(fresh.reason()).isNull();
        assertThat(price(productId)).isEqualTo(3800);
    }

    @Test
    @DisplayName("same device, sequential offline edits pushed together: all applied, no conflict")
    void sameDeviceSequentialEdits_appliedWithoutConflict() {
        UUID productId = newProduct("Riz LWW", 1000);
        Instant now = Instant.now();
        backdate(productId, now.minus(1, ChronoUnit.HOURS));

        SyncOperation first = updateOp(productId, 1100, now.minus(2, ChronoUnit.MINUTES));
        SyncOperation second = updateOp(productId, 1200, now.minus(1, ChronoUnit.MINUTES));
        SyncBatchResult result = push("device-B", first, second);

        assertThat(result.results()).extracting(SyncOperationResult::status)
                .containsOnly(SyncOperationStatus.APPLIED);
        assertThat(result.results()).extracting(SyncOperationResult::reason).containsOnlyNulls();
        assertThat(price(productId)).isEqualTo(1200);
        assertThat(conflictTypes(first.operationId())).isEmpty();
        assertThat(conflictTypes(second.operationId())).isEmpty();
        assertThat(jdbc.queryForObject("SELECT device_id FROM \"" + schema + "\".sync_operations_log WHERE id = ?",
                String.class, second.operationId())).isEqualTo("device-B");
    }

    @Test
    @DisplayName("another device overwrites an edit it had not pulled: conflict logged")
    void otherDeviceOverwrite_conflictLogged() {
        UUID productId = newProduct("Huile LWW", 2000);
        Instant now = Instant.now();
        backdate(productId, now.minus(1, ChronoUnit.HOURS));
        push("device-B", updateOp(productId, 2100, now.minus(2, ChronoUnit.MINUTES)));

        // device-C last pulled before device-B's edit reached the server: it never saw it.
        String deviceC = "device-C-" + UUID.randomUUID().toString().substring(0, 8);
        jdbc.update("INSERT INTO public.user_sync_state (device_id, user_id, tenant_id, last_pull_at, updated_at) "
                + "VALUES (?, ?, ?, ?, ?)", deviceC, actorId, schema,
                Timestamp.from(now.minus(30, ChronoUnit.MINUTES)), Timestamp.from(now));

        SyncOperation overwrite = updateOp(productId, 2200, now.minus(1, ChronoUnit.MINUTES));
        SyncOperationResult result = push(deviceC, overwrite).results().get(0);

        assertThat(result.status()).isEqualTo(SyncOperationStatus.APPLIED);
        assertThat(price(productId)).isEqualTo(2200);
        assertThat(conflictTypes(overwrite.operationId())).containsExactly("LAST_WRITE_WINS");
    }

    @Test
    @DisplayName("store (updated_at stamped at flush): same-device sequential edits in one push all applied")
    void storeSequentialEdits_sameDevice_allApplied() {
        UUID storeId = jdbc.queryForObject("SELECT id FROM \"" + schema + "\".stores LIMIT 1", UUID.class);
        Instant now = Instant.now();
        jdbc.update("UPDATE \"" + schema + "\".stores SET updated_at = ? WHERE id = ?",
                Timestamp.from(now.minus(1, ChronoUnit.HOURS)), storeId);

        SyncBatchResult result = push("device-D",
                storeOp(storeId, "Boutique Centre", now.minus(2, ChronoUnit.MINUTES)),
                storeOp(storeId, "Boutique Marché", now.minus(1, ChronoUnit.MINUTES)));

        assertThat(result.results()).extracting(SyncOperationResult::status)
                .containsOnly(SyncOperationStatus.APPLIED);
        assertThat(result.results()).extracting(SyncOperationResult::reason).containsOnlyNulls();
        assertThat(jdbc.queryForObject("SELECT name FROM \"" + schema + "\".stores WHERE id = ?",
                String.class, storeId)).isEqualTo("Boutique Marché");
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private static SyncOperation storeOp(UUID storeId, String name, Instant clientTimestamp) {
        return new SyncOperation(UUID.randomUUID().toString(), "UPDATE_STORE", storeId.toString(),
                Map.of("storeId", storeId.toString(), "name", name), clientTimestamp);
    }

    private UUID newProduct(String name, int price) {
        return createProduct.execute(new CreateProductDto(name, null, null, null, price, null, null, 0,
                actorId, 0, null, "OWNER", null, null, null)).getId();
    }

    private static SyncOperation updateOp(UUID productId, int price, Instant clientTimestamp) {
        return new SyncOperation(UUID.randomUUID().toString(), "UPDATE_PRODUCT", productId.toString(),
                Map.of("productId", productId.toString(), "price", price), clientTimestamp);
    }

    private SyncBatchResult push(String deviceId, SyncOperation... ops) {
        return syncUseCase.pushBatch(new PushBatchCommand(actorId, schema, deviceId, List.of(ops)));
    }

    private void backdate(UUID productId, Instant at) {
        jdbc.update("UPDATE \"" + schema + "\".products SET updated_at = ? WHERE id = ?", Timestamp.from(at), productId);
    }

    private Instant updatedAt(UUID productId) {
        return jdbc.queryForObject("SELECT updated_at FROM \"" + schema + "\".products WHERE id = ?",
                Timestamp.class, productId).toInstant();
    }

    private Integer price(UUID productId) {
        return jdbc.queryForObject("SELECT price FROM \"" + schema + "\".products WHERE id = ?",
                Integer.class, productId);
    }

    private List<String> conflictTypes(String operationId) {
        return jdbc.queryForList("SELECT conflict_type FROM \"" + schema + "\".sync_conflicts_log "
                + "WHERE operation_id = ?", String.class, operationId);
    }
}
