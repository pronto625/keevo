package com.keevo.shared.domain.exception;

/**
 * ErrorCode — Catalogue of all domain error codes used by DomainException.
 *
 * <p>Pure Java — NO Spring/framework imports.
 * New codes MUST be added here (never use raw strings in throw sites).
 */
public enum ErrorCode {

    // ── Money / Financial ─────────────────────────────────────────
    INVALID_AMOUNT,
    NEGATIVE_MONETARY_VALUE,

    // ── Identity / Auth ───────────────────────────────────────────
    USER_NOT_FOUND,
    USER_ALREADY_EXISTS,
    EMAIL_ALREADY_EXISTS,
    INVALID_PHONE_NUMBER,
    INVALID_PASSWORD,
    INVALID_CREDENTIALS,
    ACCOUNT_LOCKED,
    TOKEN_EXPIRED,
    TOKEN_INVALID,              // Story 1.7 — loginToken used as accessToken, or invalid scope
    REFRESH_TOKEN_INVALID,
    UNAUTHORIZED,

    // ── Tenant ────────────────────────────────────────────────────
    TENANT_NOT_FOUND,
    TENANT_ALREADY_EXISTS,
    TENANT_PROVISION_FAILED,

    // ── Membership (Story 1.7) ─────────────────────────────────────
    MEMBERSHIP_ALREADY_EXISTS,  // UNIQUE(user_id, tenant_id) violated
    MEMBERSHIP_NOT_FOUND,       // user is not a member of the requested tenant
    // ── Onboarding ────────────────────────────────────────────────────────
    ONBOARDING_ALREADY_COMPLETED,
    SECTOR_TEMPLATE_NOT_FOUND,
    // ── Catalog ───────────────────────────────────────────────────
    PRODUCT_NOT_FOUND,
    CATEGORY_NOT_FOUND,
    CLIENT_NOT_FOUND,
    SUPPLIER_NOT_FOUND,    PRODUCT_NAME_ALREADY_EXISTS, // Story 2.4 — name uniqueness (DRAFT or ACTIVE)
    CSV_PARSE_ERROR,             // Story 2.4 — malformed CSV file (422)
    // ── Inventory ─────────────────────────────────────────────────
    INSUFFICIENT_STOCK,
    STOCK_NOT_FOUND,

    // ── Employee (Story 3.5) ──────────────────────────────────────
    EMPLOYEE_NOT_FOUND,
    PASSWORD_CHANGE_REQUIRED,
    STORE_REASSIGNED,
    ACCOUNT_INACTIVE,

    // ── Store ─────────────────────────────────────────────────────
    STORE_NOT_FOUND,
    WAREHOUSE_ALREADY_EXISTS,  // HTTP 409 — tenant already has one warehouse (Story 3.1)

    // ── Transfer (Story 3.3) ──────────────────────────────────────────────────
    STORE_NOT_ACTIVE,           // HTTP 422 — source or destination store is deactivated
    SAME_SOURCE_DESTINATION,    // HTTP 422 — sourceStoreId == destinationStoreId
    TRANSFER_NOT_FOUND,         // HTTP 404 — transfer id does not exist (two-step flow)
    TRANSFER_INVALID_STATUS,    // HTTP 422 — transfer is not IN_TRANSIT, cannot be completed

    // ── Sync ──────────────────────────────────────────────────────
    SYNC_CONFLICT,
    OFFLINE_LIMIT_EXCEEDED,

    // ── Subscription / Plan Limits ────────────────────────────────
    SUBSCRIPTION_NOT_FOUND, // HTTP 404 — no active subscription for tenant (Story 2.4)
    PLAN_LIMIT_EXCEEDED,    // HTTP 403 — store/product/employee limit reached on Free plan
    ACCOUNT_SUSPENDED,      // HTTP 403 — tenant status = SUSPENDED (manual admin lockout)
    RATE_LIMIT_EXCEEDED,    // HTTP 429 — >100 req/min per tenant
    // ── Audit ─────────────────────────────────────────────────────────────
    AUDIT_IMMUTABLE,        // HTTP 403 — attempt to modify or delete an immutable audit entry
    // ── Generic ───────────────────────────────────────────────────
    NOT_FOUND,
    VALIDATION_FAILED,
    VALIDATION_ERROR,
    INTERNAL_ERROR,
    /**
     * FORBIDDEN — authenticated user does not have the required role.
     * Maps to HTTP 403. Use instead of UNAUTHORIZED (401) when user IS
     * authenticated but lacks permission (H2 fix — role guards).
     */
    FORBIDDEN;              // HTTP 403 — wrong role (authenticated but not authorised)
}
