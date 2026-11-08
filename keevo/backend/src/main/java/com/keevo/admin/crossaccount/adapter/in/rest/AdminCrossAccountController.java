package com.keevo.admin.crossaccount.adapter.in.rest;

import com.keevo.admin.crossaccount.domain.model.CrossAccountViews.CustomerDetail;
import com.keevo.admin.crossaccount.domain.model.CrossAccountViews.CustomerItem;
import com.keevo.admin.crossaccount.domain.model.CrossAccountViews.ExpenseDetail;
import com.keevo.admin.crossaccount.domain.model.CrossAccountViews.ExpensePage;
import com.keevo.admin.crossaccount.domain.model.CrossAccountViews.ProductDetail;
import com.keevo.admin.crossaccount.domain.model.CrossAccountViews.ProductItem;
import com.keevo.admin.crossaccount.domain.port.in.CrossAccountUseCase;
import com.keevo.admin.crosstenant.CursorPage;
import com.keevo.shared.infrastructure.security.CurrentActor;
import com.keevo.shared.infrastructure.web.ApiResponseWrapper;
import com.keevo.shared.module.EntitlementExempt;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Story 20.13 — super-admin cross-account lists inherited from Bref ({@code GET /customers}, {@code /customers/:id},
 * {@code /products}, {@code /expenses}). Read-only; SUPER_ADMIN checked explicitly behind the SecurityConfig URL rule;
 * no business logic (validation in the service). Lists are keyset-paginated ({@code cursor} + {@code limit}).
 */
@Tag(name = "Admin — Cross-account lists", description = "Super Admin customers, products and expenses of every account")
@EntitlementExempt("super-admin")
@RestController
@RequestMapping("/api/v1/admin")
public class AdminCrossAccountController {

    private final CrossAccountUseCase useCase;

    public AdminCrossAccountController(CrossAccountUseCase useCase) {
        this.useCase = useCase;
    }

    @Operation(summary = "Customers of every account (keyset)", security = @SecurityRequirement(name = "bearerAuth"))
    @GetMapping("/customers")
    public ResponseEntity<ApiResponseWrapper<CursorPage<CustomerItem>>> listCustomers(
            @RequestParam(required = false) String tenantId, @RequestParam(required = false) String storeId,
            @RequestParam(required = false) String q, @RequestParam(required = false) String cursor,
            @RequestParam(required = false) String limit) {
        CurrentActor.requireSuperAdmin();
        return ResponseEntity.ok(ApiResponseWrapper.ok(useCase.listCustomers(
                new CrossAccountUseCase.CustomerQuery(tenantId, storeId, q, cursor, limit))));
    }

    @Operation(summary = "One customer of an account", security = @SecurityRequirement(name = "bearerAuth"))
    @GetMapping("/tenants/{tenantId}/customers/{customerId}")
    public ResponseEntity<ApiResponseWrapper<CustomerDetail>> getCustomer(@PathVariable String tenantId,
                                                                          @PathVariable String customerId) {
        CurrentActor.requireSuperAdmin();
        return ResponseEntity.ok(ApiResponseWrapper.ok(useCase.getCustomer(tenantId, customerId)));
    }

    @Operation(summary = "Products of every account (keyset)", security = @SecurityRequirement(name = "bearerAuth"))
    @GetMapping("/products")
    public ResponseEntity<ApiResponseWrapper<CursorPage<ProductItem>>> listProducts(
            @RequestParam(required = false) String tenantId, @RequestParam(required = false) String q,
            @RequestParam(required = false) String status, @RequestParam(required = false) String cursor,
            @RequestParam(required = false) String limit) {
        CurrentActor.requireSuperAdmin();
        return ResponseEntity.ok(ApiResponseWrapper.ok(useCase.listProducts(
                new CrossAccountUseCase.ProductQuery(tenantId, q, status, cursor, limit))));
    }

    @Operation(summary = "One product of an account", security = @SecurityRequirement(name = "bearerAuth"))
    @GetMapping("/tenants/{tenantId}/products/{productId}")
    public ResponseEntity<ApiResponseWrapper<ProductDetail>> getProduct(@PathVariable String tenantId,
                                                                        @PathVariable String productId) {
        CurrentActor.requireSuperAdmin();
        return ResponseEntity.ok(ApiResponseWrapper.ok(useCase.getProduct(tenantId, productId)));
    }

    @Operation(summary = "Expenses of every account (keyset) and their total",
            security = @SecurityRequirement(name = "bearerAuth"))
    @GetMapping("/expenses")
    public ResponseEntity<ApiResponseWrapper<ExpensePage>> listExpenses(
            @RequestParam(required = false) String tenantId, @RequestParam(required = false) String storeId,
            @RequestParam(required = false) String category, @RequestParam(required = false) String from,
            @RequestParam(required = false) String to, @RequestParam(required = false) String q,
            @RequestParam(required = false) String cursor, @RequestParam(required = false) String limit) {
        CurrentActor.requireSuperAdmin();
        return ResponseEntity.ok(ApiResponseWrapper.ok(useCase.listExpenses(
                new CrossAccountUseCase.ExpenseQuery(tenantId, storeId, category, from, to, q, cursor, limit))));
    }

    @Operation(summary = "One expense of an account", security = @SecurityRequirement(name = "bearerAuth"))
    @GetMapping("/tenants/{tenantId}/expenses/{expenseId}")
    public ResponseEntity<ApiResponseWrapper<ExpenseDetail>> getExpense(@PathVariable String tenantId,
                                                                        @PathVariable String expenseId) {
        CurrentActor.requireSuperAdmin();
        return ResponseEntity.ok(ApiResponseWrapper.ok(useCase.getExpense(tenantId, expenseId)));
    }
}
