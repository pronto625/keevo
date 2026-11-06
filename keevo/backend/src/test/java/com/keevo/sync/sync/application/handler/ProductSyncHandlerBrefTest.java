package com.keevo.sync.sync.application.handler;

import com.keevo.catalog.api.CreateProductInput;
import com.keevo.catalog.api.ProductCommandPort;
import com.keevo.catalog.api.ProductQueryPort;
import com.keevo.catalog.api.ProductState;
import com.keevo.catalog.api.ProductView;
import com.keevo.catalog.api.UpdateProductInput;
import com.keevo.shared.application.port.sync.SyncOperation;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import com.keevo.shared.application.port.sync.SyncOperationStatus;
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
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/** Story 20.1 — product sync: barcode payload, merged products never restored nor resurrected. */
@ExtendWith(MockitoExtension.class)
@DisplayName("ProductSyncHandler — code-barres et produits fusionnés (20.1)")
class ProductSyncHandlerBrefTest {

    private static final String TENANT = "kv_bref02";
    private static final UUID ACTOR = UUID.randomUUID();

    @Mock private ProductCommandPort productCommand;
    @Mock private ProductQueryPort productQuery;
    private ProductSyncHandler handler;

    @BeforeEach
    void setUp() {
        TenantContext.setCurrentTenant(TENANT);
        SyncTestAuth.owner();
        handler = new ProductSyncHandler(productCommand, productQuery);
    }

    @AfterEach
    void tearDown() {
        SyncTestAuth.clear();
        TenantContext.clear();
    }

    private static ProductView view(UUID id, String name, boolean archived, UUID mergedInto) {
        return new ProductView(id, name, null, "KEV-ABC123", null, 500, 0, 0, 0, archived, ProductState.ACTIVE, 0,
                Instant.now(), Instant.now(), true, null, "nm:" + name.toLowerCase(), true, mergedInto);
    }

    @Test
    @DisplayName("CREATE_PRODUCT transmet le barcode")
    void create_passes_the_barcode() {
        UUID id = UUID.randomUUID();
        when(productCommand.create(any(), any())).thenReturn(view(id, "Savon", false, null));

        var result = handler.handle(new SyncOperation("op-c", "CREATE_PRODUCT", id.toString(),
                Map.of("id", id.toString(), "name", "Savon", "price", 500, "barcode", "6111234567890"),
                Instant.now()), ACTOR, TENANT);

        assertThat(result.status()).isEqualTo(SyncOperationStatus.APPLIED);
        var input = ArgumentCaptor.forClass(CreateProductInput.class);
        verify(productCommand).create(eq(TENANT), input.capture());
        assertThat(input.getValue().barcode()).isEqualTo("6111234567890");
    }

    @Test
    @DisplayName("UPDATE_PRODUCT sans barcode (V1) → null transmis (inchangé) ; barcode non texte → rejeté")
    void update_without_barcode_keeps_it() {
        UUID id = UUID.randomUUID();
        when(productCommand.update(any(), any())).thenReturn(view(id, "Savon", false, null));

        handler.handle(new SyncOperation("op-u", "UPDATE_PRODUCT", id.toString(),
                Map.of("productId", id.toString(), "price", 700), Instant.now()), ACTOR, TENANT);

        var input = ArgumentCaptor.forClass(UpdateProductInput.class);
        verify(productCommand).update(eq(TENANT), input.capture());
        assertThat(input.getValue().barcode()).isNull();

        var rejected = handler.handle(new SyncOperation("op-u2", "UPDATE_PRODUCT", id.toString(),
                Map.of("productId", id.toString(), "barcode", 42), Instant.now()), ACTOR, TENANT);
        assertThat(rejected.status()).isEqualTo(SyncOperationStatus.REJECTED);
    }

    @Test
    @DisplayName("UNARCHIVE_PRODUCT d'un produit fusionné : refus du cas d'usage → REJECTED PRODUCT_MERGED, sans pré-requête")
    void unarchive_merged_is_rejected() {
        UUID a = UUID.randomUUID();
        when(productCommand.unarchive(TENANT, a, ACTOR)).thenThrow(DomainException.userFacing(
                ErrorCode.PRODUCT_MERGE_INVALID, "Ce produit a été fusionné avec « Savon Lux »"));

        var result = handler.handle(new SyncOperation("op-x", "UNARCHIVE_PRODUCT", a.toString(),
                Map.of("productId", a.toString()), Instant.now()), ACTOR, TENANT);

        assertThat(result.status()).isEqualTo(SyncOperationStatus.REJECTED);
        assertThat(result.reason()).isEqualTo("PRODUCT_MERGED");
        verifyNoInteractions(productQuery);
    }

    @Test
    @DisplayName("ARCHIVE_PRODUCT : délégué au cas d'usage (no-op pour un produit fusionné), sans pré-requête")
    void archive_is_delegated_without_pre_query() {
        UUID a = UUID.randomUUID();

        var result = handler.handle(new SyncOperation("op-y", "ARCHIVE_PRODUCT", a.toString(),
                Map.of("productId", a.toString()), Instant.now()), ACTOR, TENANT);

        assertThat(result.status()).isEqualTo(SyncOperationStatus.APPLIED);
        verify(productCommand).archive(TENANT, a, ACTOR);
        verifyNoInteractions(productQuery);
    }

    @Test
    @DisplayName("CREATE_PRODUCT au nom d'un produit fusionné dont la cible a été archivée → la cible est restaurée, jamais A")
    void create_with_a_merged_name_restores_the_archived_target() {
        UUID local = UUID.randomUUID();
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        when(productQuery.findByName(TENANT, "Savon")).thenReturn(Optional.of(view(a, "Savon", true, b)));
        when(productQuery.findById(TENANT, b)).thenReturn(Optional.of(view(b, "Savon Lux", true, null)));
        when(productCommand.unarchive(TENANT, b, ACTOR)).thenReturn(true);

        Map<String, Object> payload = new HashMap<>();
        payload.put("id", local.toString());
        payload.put("name", "Savon");
        var result = handler.handle(new SyncOperation("op-r", "CREATE_PRODUCT", local.toString(), payload,
                Instant.now()), ACTOR, TENANT);

        assertThat(result.status()).isEqualTo(SyncOperationStatus.APPLIED);
        assertThat(result.serverEntityId()).isEqualTo(b.toString());
        assertThat(result.reason()).isEqualTo(ProductSyncHandler.MERGED_INTO_PREFIX + b);
        verify(productCommand).unarchive(TENANT, b, ACTOR);
        verify(productCommand, never()).unarchive(TENANT, a, ACTOR);
    }

    @Test
    @DisplayName("CREATE_PRODUCT au nom d'un produit fusionné → alias vers la cible, A reste archivé")
    void create_with_a_merged_name_lands_on_the_target() {
        UUID local = UUID.randomUUID();
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        when(productQuery.findByName(TENANT, "Savon")).thenReturn(Optional.of(view(a, "Savon", true, b)));
        when(productQuery.findById(TENANT, b)).thenReturn(Optional.of(view(b, "Savon Lux", false, null)));

        Map<String, Object> payload = new HashMap<>();
        payload.put("id", local.toString());
        payload.put("name", "Savon");
        var result = handler.handle(new SyncOperation("op-z", "CREATE_PRODUCT", local.toString(), payload,
                Instant.now()), ACTOR, TENANT);

        assertThat(result.status()).isEqualTo(SyncOperationStatus.APPLIED);
        assertThat(result.serverEntityId()).isEqualTo(b.toString());
        assertThat(result.reason()).isEqualTo(ProductSyncHandler.MERGED_INTO_PREFIX + b);
        verify(productCommand, never()).unarchive(any(), any(), any());
        verify(productCommand, never()).create(any(), any());
    }
}
