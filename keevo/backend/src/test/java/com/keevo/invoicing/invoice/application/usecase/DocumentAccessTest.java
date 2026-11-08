package com.keevo.invoicing.invoice.application.usecase;

import com.keevo.invoicing.invoice.domain.model.DocumentScope;
import com.keevo.invoicing.invoice.domain.port.out.InvoiceRepository;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import com.keevo.shared.infrastructure.security.AuthDetails;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Story 20.14 — visibility of documents per role (user decision 2026-11-08, as Bref): owner all, manager / viewer
 * their store, employee their own documents, member without store refused; out of scope → 404.
 */
class DocumentAccessTest {

    private static final UUID A = UUID.randomUUID();
    private static final UUID B = UUID.randomUUID();
    private static final UUID ME = UUID.randomUUID();
    private static final UUID OTHER = UUID.randomUUID();

    private final InvoiceRepository invoices = mock(InvoiceRepository.class);
    private final DocumentAccess access = new DocumentAccess(invoices);

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    private static void actor(UUID user, String role, UUID store) {
        UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(user, null,
                List.of(new SimpleGrantedAuthority("ROLE_" + role)));
        auth.setDetails(new AuthDetails("Paul", store));
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    private static void notFound(Runnable call, ErrorCode code) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(DomainException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(code));
    }

    @Test
    @DisplayName("scope per role: owner / no actor all, manager and viewer their store, employee their documents")
    void scopes() {
        assertThat(access.current()).isEqualTo(DocumentScope.ALL); // system work (bridge, jobs)
        actor(ME, "OWNER", null);
        assertThat(access.current()).isEqualTo(DocumentScope.ALL);
        actor(ME, "MANAGER", A);
        assertThat(access.current()).isEqualTo(DocumentScope.store(A));
        actor(ME, "VIEWER", A);
        assertThat(access.current()).isEqualTo(DocumentScope.store(A));
        actor(ME, "EMPLOYEE", A);
        assertThat(access.current()).isEqualTo(DocumentScope.creator(ME));
    }

    @Test
    @DisplayName("member without store: 403 with a French message; the pull reads nothing instead")
    void memberWithoutStore() {
        actor(ME, "EMPLOYEE", null);
        assertThatThrownBy(access::current).isInstanceOfSatisfying(DomainException.class, e -> {
            assertThat(e.getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN);
            assertThat(e.getUserMessage()).isEqualTo(DocumentAccess.NO_STORE);
        });
        assertThat(access.forPull()).isEqualTo(DocumentScope.NONE);
        assertThat(DocumentScope.NONE.sql("i").condition()).isEqualTo("FALSE");
    }

    @Test
    @DisplayName("explicit store filter: owner only (Bref onlyOwnerShopId) — a member gets 403, even with its store")
    void storeFilter() {
        actor(ME, "OWNER", null);
        assertThat(access.forList(A)).isEqualTo(DocumentScope.ALL);
        actor(ME, "MANAGER", A);
        assertThat(access.forList(null)).isEqualTo(DocumentScope.store(A));
        assertThatThrownBy(() -> access.forList(A)).isInstanceOfSatisfying(DomainException.class,
                e -> assertThat(e.getUserMessage()).isEqualTo(DocumentAccess.ONLY_OWNER_STORE_FILTER));
    }

    @Test
    @DisplayName("one document: in scope → nothing; out of scope or unknown → 404 INVOICE_NOT_FOUND; owner: no read")
    void oneDocument() {
        UUID mine = UUID.randomUUID();
        UUID theirs = UUID.randomUUID();
        UUID otherStore = UUID.randomUUID();
        when(invoices.ownerOf(mine)).thenReturn(Optional.of(new DocumentScope.Owner(ME, A)));
        when(invoices.ownerOf(theirs)).thenReturn(Optional.of(new DocumentScope.Owner(OTHER, A)));
        when(invoices.ownerOf(otherStore)).thenReturn(Optional.of(new DocumentScope.Owner(ME, B)));

        actor(ME, "EMPLOYEE", A);
        assertThatCode(() -> access.requireInvoice(mine)).doesNotThrowAnyException();
        assertThatCode(() -> access.requireInvoice(otherStore)).doesNotThrowAnyException(); // created by me
        notFound(() -> access.requireInvoice(theirs), ErrorCode.INVOICE_NOT_FOUND);
        notFound(() -> access.requireInvoice(UUID.randomUUID()), ErrorCode.INVOICE_NOT_FOUND);

        actor(OTHER, "MANAGER", A);
        assertThatCode(() -> access.requireInvoice(mine)).doesNotThrowAnyException();
        assertThatCode(() -> access.requireInvoice(theirs)).doesNotThrowAnyException();
        notFound(() -> access.requireInvoice(otherStore), ErrorCode.INVOICE_NOT_FOUND);
    }

    @Test
    @DisplayName("credit note: through its invoice; creation with a taken id of another's document → 404, free id ok")
    void creditNoteAndCreation() {
        UUID note = UUID.randomUUID();
        UUID taken = UUID.randomUUID();
        when(invoices.ownerOfCreditNote(note)).thenReturn(Optional.of(new DocumentScope.Owner(OTHER, A)));
        when(invoices.ownerOf(taken)).thenReturn(Optional.of(new DocumentScope.Owner(OTHER, A)));
        when(invoices.ownerOf(ME)).thenReturn(Optional.empty());

        actor(ME, "EMPLOYEE", A);
        notFound(() -> access.requireCreditNote(note), ErrorCode.CREDIT_NOTE_NOT_FOUND);
        notFound(() -> access.requireCreatable(taken), ErrorCode.INVOICE_NOT_FOUND);
        assertThatCode(() -> access.requireCreatable(ME)).doesNotThrowAnyException();
        actor(ME, "MANAGER", A);
        assertThatCode(() -> access.requireCreditNote(note)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("owner: never a read for one document")
    void ownerReadsNothing() {
        actor(ME, "OWNER", null);
        access.requireInvoice(UUID.randomUUID());
        access.requireCreditNote(UUID.randomUUID());
        verifyNoInteractions(invoices);
    }

    @Test
    @DisplayName("SQL predicate: one indexed equality per scope")
    void sql() {
        assertThat(DocumentScope.ALL.sql("i").condition()).isEqualTo("TRUE");
        assertThat(DocumentScope.creator(ME).sql(null)).isEqualTo(new DocumentScope.Sql("created_by = ?", List.of(ME)));
        assertThat(DocumentScope.store(A).sql("i")).isEqualTo(new DocumentScope.Sql("i.store_id = ?", List.of(A)));
        assertThat(DocumentScope.store(A).sees(new DocumentScope.Owner(OTHER, A))).isTrue();
        assertThat(DocumentScope.store(A).sees(new DocumentScope.Owner(OTHER, null))).isFalse();
        assertThat(DocumentScope.NONE.sees(new DocumentScope.Owner(ME, A))).isFalse();
    }
}
