package com.keevo.admin.crossaccount.application.service;

import com.keevo.admin.crossaccount.domain.model.CrossAccountViews;
import com.keevo.admin.crossaccount.domain.model.CrossAccountViews.ExpensePage;
import com.keevo.admin.crossaccount.domain.port.in.CrossAccountUseCase.CustomerQuery;
import com.keevo.admin.crossaccount.domain.port.in.CrossAccountUseCase.ExpenseQuery;
import com.keevo.admin.crossaccount.domain.port.in.CrossAccountUseCase.ProductQuery;
import com.keevo.admin.crossaccount.domain.port.out.CrossAccountReadPort;
import com.keevo.admin.crosstenant.CrossTenantSql;
import com.keevo.admin.crosstenant.CursorPage;
import com.keevo.admin.crosstenant.TenantBranch;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("20.13 — cross-account lists service (Bref customer / product / expense admin)")
class CrossAccountServiceTest {

    private static final String TENANT = "6f1c2a52-3c1e-4b8e-9a77-0d4f2c9b1e11";
    private static final String ID = "0b6e3f1c-8a8d-4c51-9a0e-1d2f3a4b5c6d";

    @Mock CrossAccountReadPort reader;
    @Mock CrossTenantSql crossTenant;

    CrossAccountService service;

    @BeforeEach
    void setUp() {
        service = new CrossAccountService(reader, crossTenant);
    }

    @Test
    @DisplayName("customers: filters validated and normalised, cursor bound to them")
    void customersCriteria() {
        when(reader.customers(any())).thenReturn(new CursorPage<>(List.of(), null, false));

        service.listCustomers(new CustomerQuery(TENANT.toUpperCase(), null, "  awa  ", "c", "5"));

        ArgumentCaptor<CrossAccountReadPort.CustomerCriteria> c =
                ArgumentCaptor.forClass(CrossAccountReadPort.CustomerCriteria.class);
        verify(reader).customers(c.capture());
        assertThat(c.getValue().tenantId()).isEqualTo(TENANT);
        assertThat(c.getValue().search()).isEqualTo("awa");
        assertThat(c.getValue().limit()).isEqualTo(5);
        assertThat(c.getValue().cursor()).isEqualTo("c");
        assertThat(c.getValue().filters()).contains(TENANT, "awa");
    }

    @Test
    @DisplayName("bad account / store / limit / status / category / dates → 400, the reader is never called")
    void invalidParams() {
        assertInvalid(() -> service.listCustomers(new CustomerQuery("x", null, null, null, null)));
        assertInvalid(() -> service.listCustomers(new CustomerQuery(null, "y", null, null, null)));
        assertInvalid(() -> service.listProducts(new ProductQuery(null, null, "deleted", null, null)));
        assertInvalid(() -> service.listProducts(new ProductQuery(null, null, null, null, "500")));
        assertInvalid(() -> service.listExpenses(new ExpenseQuery(null, null, "food", null, null, null, null, null)));
        assertInvalid(() -> service.listExpenses(new ExpenseQuery(null, null, null, "2026-11-09", "2026-11-01", null,
                null, null)));
        assertInvalid(() -> service.listExpenses(new ExpenseQuery(null, null, null, "09/11/2026", null, null, null,
                null)));
        verifyNoInteractions(reader);
    }

    @Test
    @DisplayName("products: archived status and expenses: category / dates passed to the reader")
    void productsAndExpenses() {
        when(reader.products(any())).thenReturn(new CursorPage<>(List.of(), null, false));
        when(reader.expenses(any())).thenReturn(new ExpensePage(new CursorPage<>(List.of(), null, false),
                BigDecimal.ZERO));

        service.listProducts(new ProductQuery(null, null, "ARCHIVED", null, null));
        service.listExpenses(new ExpenseQuery(null, null, "rent", "2026-11-01", "2026-11-08", null, null, null));

        ArgumentCaptor<CrossAccountReadPort.ProductCriteria> p =
                ArgumentCaptor.forClass(CrossAccountReadPort.ProductCriteria.class);
        verify(reader).products(p.capture());
        assertThat(p.getValue().archived()).isTrue();
        assertThat(p.getValue().limit()).isEqualTo(20);
        ArgumentCaptor<CrossAccountReadPort.ExpenseCriteria> e =
                ArgumentCaptor.forClass(CrossAccountReadPort.ExpenseCriteria.class);
        verify(reader).expenses(e.capture());
        assertThat(e.getValue().category()).isEqualTo("rent");
        assertThat(e.getValue().from()).isEqualTo(LocalDate.of(2026, 11, 1));
        assertThat(e.getValue().to()).isEqualTo(LocalDate.of(2026, 11, 8));
    }

    @Test
    @DisplayName("details: unknown record → 404 with its own code; malformed id → 400")
    void details() {
        TenantBranch tenant = new TenantBranch(TENANT, "Alpha", "kv_aaaaaa", "ACTIVE", null);
        when(crossTenant.requireTenant(anyString())).thenReturn(tenant);
        when(reader.customer(eq(tenant), eq(ID))).thenReturn(Optional.empty());
        when(reader.product(eq(tenant), eq(ID))).thenReturn(Optional.empty());
        when(reader.expense(eq(tenant), eq(ID))).thenReturn(Optional.empty());

        assertCode(() -> service.getCustomer(TENANT, ID), ErrorCode.CLIENT_NOT_FOUND);
        assertCode(() -> service.getProduct(TENANT, ID), ErrorCode.PRODUCT_NOT_FOUND);
        assertCode(() -> service.getExpense(TENANT, ID), ErrorCode.EXPENSE_NOT_FOUND);
        assertCode(() -> service.getCustomer(TENANT, "nope"), ErrorCode.VALIDATION_ERROR);
    }

    @Test
    @DisplayName("labels: product state and client type in French")
    void labels() {
        assertThat(CrossAccountViews.productStatusLabel("ACTIVE", true)).isEqualTo("Archivé");
        assertThat(CrossAccountViews.productStatusLabel("DRAFT", false)).isEqualTo("Brouillon");
        assertThat(CrossAccountViews.productStatusLabel("ACTIVE", false)).isEqualTo("Actif");
        assertThat(CrossAccountViews.clientTypeLabel("company")).isEqualTo("Entreprise");
        assertThat(CrossAccountViews.clientTypeLabel("PERSON")).isEqualTo("Particulier");
        assertThat(CrossAccountViews.clientTypeLabel(null)).isNull();
    }

    private static void assertInvalid(Runnable call) {
        assertCode(call, ErrorCode.VALIDATION_ERROR);
    }

    private static void assertCode(Runnable call, ErrorCode code) {
        assertThatThrownBy(call::run).isInstanceOf(DomainException.class)
                .extracting(e -> ((DomainException) e).getErrorCode()).isEqualTo(code);
    }
}
