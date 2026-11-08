package com.keevo.invoicing.invoice.application.usecase;

import com.keevo.invoicing.invoice.domain.model.ClientInfo;
import com.keevo.invoicing.invoice.domain.model.Invoice;
import com.keevo.invoicing.invoice.domain.model.InvoicingSettings;
import com.keevo.invoicing.invoice.domain.model.LineInput;
import com.keevo.invoicing.invoice.domain.model.VatTreatment;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import com.keevo.shared.infrastructure.security.AuthDetails;
import com.keevo.store.api.StoreQueryPort;
import com.keevo.store.api.StoreType;
import com.keevo.store.api.StoreView;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Story 20.14 — the store of a new document: store member → its store (another one → 403), owner → the requested
 * active store (else 400 field storeId) or the oldest active shop; the store names of a page in one lookup.
 */
class DocumentStoreResolverTest {

    private static final String TENANT = "kv_abc123";
    private static final UUID A = UUID.randomUUID();
    private static final UUID B = UUID.randomUUID();
    private static final UUID W = UUID.randomUUID();

    private final StoreQueryPort stores = mock(StoreQueryPort.class);
    private final DocumentStoreResolver resolver = new DocumentStoreResolver(stores);

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    private static void actor(String role, UUID store) {
        UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(UUID.randomUUID(), null,
                List.of(new SimpleGrantedAuthority("ROLE_" + role)));
        if (store != null) {
            auth.setDetails(new AuthDetails("Paul", store));
        }
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    private static StoreView store(UUID id, StoreType type, boolean active, String createdAt) {
        return new StoreView(id, "Boutique", type, null, null, active, Instant.parse(createdAt),
                Instant.parse(createdAt));
    }

    @Test
    @DisplayName("store member: its own store, nothing read; the same store requested is fine; another → 403")
    void member() {
        actor("EMPLOYEE", B);
        assertThat(resolver.forNewDocument(null, TENANT)).isEqualTo(B);
        assertThat(resolver.forNewDocument(B, TENANT)).isEqualTo(B);
        assertThatThrownBy(() -> resolver.forNewDocument(A, TENANT)).isInstanceOfSatisfying(DomainException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN));
        actor("MANAGER", A);
        assertThat(resolver.forNewDocument(null, TENANT)).isEqualTo(A);
        verifyNoInteractions(stores);
    }

    @Test
    @DisplayName("store member without store in its token: no store (the document is still made)")
    void memberWithoutStore() {
        actor("EMPLOYEE", null);
        assertThat(resolver.forNewDocument(null, TENANT)).isNull();
        verifyNoInteractions(stores);
    }

    @Test
    @DisplayName("owner: the requested active store; unknown or inactive → VALIDATION_ERROR field storeId")
    void ownerRequested() {
        actor("OWNER", null);
        when(stores.findById(TENANT, B)).thenReturn(Optional.of(store(B, StoreType.STORE, true, "2026-01-02T00:00:00Z")));
        when(stores.findById(TENANT, A)).thenReturn(Optional.of(store(A, StoreType.STORE, false, "2026-01-01T00:00:00Z")));
        assertThat(resolver.forNewDocument(B, TENANT)).isEqualTo(B);
        for (UUID bad : List.of(A, UUID.randomUUID())) {
            assertThatThrownBy(() -> resolver.forNewDocument(bad, TENANT)).isInstanceOfSatisfying(
                    DomainException.class, e -> assertThat(DocumentStoreResolver.isUnknownStore(e)).isTrue());
        }
    }

    @Test
    @DisplayName("owner without storeId: the oldest active shop (a warehouse only when no shop), none without store")
    void ownerDefault() {
        actor("OWNER", null);
        when(stores.findAllActive(TENANT)).thenReturn(List.of(
                store(B, StoreType.STORE, true, "2026-03-01T00:00:00Z"),
                store(W, StoreType.WAREHOUSE, true, "2025-01-01T00:00:00Z"),
                store(A, StoreType.STORE, true, "2026-02-01T00:00:00Z")));
        assertThat(resolver.forNewDocument(null, TENANT)).isEqualTo(A);

        when(stores.findAllActive(TENANT)).thenReturn(List.of(store(W, StoreType.WAREHOUSE, true,
                "2025-01-01T00:00:00Z")));
        assertThat(resolver.forNewDocument(null, TENANT)).isEqualTo(W);

        when(stores.findAllActive(TENANT)).thenReturn(List.of());
        assertThat(resolver.forNewDocument(null, TENANT)).isNull();
    }

    @Test
    @DisplayName("store names of a page: one lookup for the distinct stores, none when no document has a store")
    void names() {
        ClientInfo client = new ClientInfo(null, "Awa", null, null, null);
        List<LineInput> lines = List.of(new LineInput("Riz", BigDecimal.ONE, 1000, VatTreatment.STANDARD));
        Invoice none = Invoice.draft(UUID.randomUUID(), client, lines, InvoicingSettings.defaults(), null,
                Instant.now());
        assertThat(resolver.namesOf(List.of(none), TENANT)).isEmpty();
        verifyNoInteractions(stores);

        when(stores.namesByIds(TENANT, Set.of(A, B))).thenReturn(Map.of(A, "Akwa", B, "Bonapriso"));
        assertThat(resolver.namesOf(List.of(none.inStore(A), none.inStore(B), none.inStore(A), none), TENANT))
                .containsEntry(A, "Akwa").containsEntry(B, "Bonapriso");
        verify(stores).namesOf(TENANT, Set.of(A, B));
    }
}
