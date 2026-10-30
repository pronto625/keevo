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
    SECTOR_TEMPLATE_NOT_FOUND,    PREFERENCES_NOT_FOUND,       // Story 7.5 — tenant_preferences row missing    // ── Catalog ───────────────────────────────────────────────────
    PRODUCT_NOT_FOUND,
    CATEGORY_NOT_FOUND,
    CLIENT_NOT_FOUND,
    SUPPLIER_NOT_FOUND,    PRODUCT_NAME_ALREADY_EXISTS, // Story 2.4 — name uniqueness (DRAFT or ACTIVE)
    CSV_PARSE_ERROR,             // Story 2.4 — malformed CSV file (422)
    PRODUCT_PHOTO_TOO_LARGE,     // HTTP 413 — product photo over keevo.product-photo.max-bytes
    PRODUCT_PHOTO_INVALID,       // HTTP 415 — not a JPEG / PNG / WebP image (or corrupt)
    PRODUCT_PHOTO_NOT_FOUND,     // HTTP 404 — the product has no photo
    // ── Inventory ─────────────────────────────────────────────────
    INSUFFICIENT_STOCK,
    STOCK_NOT_FOUND,

    // ── Inventory Session (Story 6.1) ─────────────────────────────
    INVENTORY_SESSION_ALREADY_ACTIVE,    // HTTP 409 — store already has an in-progress session
    INVENTORY_SESSION_NOT_FOUND,         // HTTP 404
    INVENTORY_SESSION_NOT_IN_PROGRESS,   // HTTP 409 — cannot cancel/validate a non-active session
    INVENTORY_STORE_NOT_FOUND,           // HTTP 404 — target store does not exist
    INVENTORY_INVALID_CATEGORIES,        // HTTP 400 — one or more category IDs are invalid

    // ── Inventory Count (Story 6.2) ───────────────────────────────
    INVENTORY_COUNT_PRODUCT_NOT_IN_SCOPE, // HTTP 400 — product not in session scope

    // ── Employee (Story 3.5) ──────────────────────────────────────
    EMPLOYEE_NOT_FOUND,
    PASSWORD_CHANGE_REQUIRED,
    STORE_REASSIGNED,
    ACCOUNT_INACTIVE,
    SESSION_REVOKED,   // Story 12.2 — NFR12: token issued before tokens_valid_after cutoff (401)
    PHONE_ALREADY_REGISTERED, // Story 14.11 — phone number already taken by another user (409)
    CANNOT_CHANGE_OWN_ROLE,   // Story 14.11 — cannot change your own role (403)
    CANNOT_DEMOTE_LAST_OWNER, // Story 14.11 — cannot demote the last active owner (403)
    CANNOT_SET_OWN_PASSWORD,  // Story 14.11 — owner cannot set own password via this endpoint (403)
    INVALID_OR_EXPIRED_CODE,  // Story 14.12 — password reset code is invalid or expired (422)
    CODE_LOCKED,              // Story 14.12 — too many failed reset attempts, request new code (422)

    // ── Optimistic Lock (Story v1s-13-1) ───────────────────────────
    OPTIMISTIC_LOCK,    // HTTP 409 — concurrent modification detected, client must retry

    // ── Store ─────────────────────────────────────────────────────
    STORE_NOT_FOUND,
    WAREHOUSE_ALREADY_EXISTS,  // HTTP 409 — tenant already has one warehouse (Story 3.1)

    // ── Transfer (Story 3.3) ──────────────────────────────────────────────────
    STORE_NOT_ACTIVE,           // HTTP 422 — source or destination store is deactivated
    SAME_SOURCE_DESTINATION,    // HTTP 422 — sourceStoreId == destinationStoreId
    TRANSFER_NOT_FOUND,         // HTTP 404 — transfer id does not exist (two-step flow)
    TRANSFER_INVALID_STATUS,    // HTTP 422 — transfer is not IN_TRANSIT, cannot be completed

    // ── Sale (Story 4.1) ──────────────────────────────────────────
    SALE_NOT_FOUND,
    SALE_ALREADY_EXISTS,

    // ── Sale (Story 4.2) ──────────────────────────────────────────
    DISCOUNT_EXCEEDS_SUBTOTAL,   // HTTP 422 — discount > sum of item subtotals

    // ── Sale (Story 4.3) ──────────────────────────────────────────
    SALE_NOT_PENDING,              // HTTP 422 — trying to validate/cancel a non-pending sale
    JUSTIFICATION_REQUIRED,        // HTTP 400 — missing justification
    JUSTIFICATION_TOO_SHORT,       // HTTP 400 — justification < 10 chars
    SALE_ALREADY_CANCELLED,        // HTTP 409 — idempotency guard, sale already cancelled
    SALE_NOT_COMPLETED,            // HTTP 422 — correction only allowed on a COMPLETED sale

    // ── Reports (Story 7.2) ───────────────────────────────────────
    REPORT_NOT_FOUND,              // HTTP 404 — report not found
    REPORT_ALREADY_SENT,           // HTTP 409 — report already delivered via WhatsApp

    // ── Sale (Story 4.4) ──────────────────────────────────────────
    DAY_ALREADY_CLOSED,            // HTTP 409 — day closure already exists for storeId + date

    // ── Sync ──────────────────────────────────────────────────────
    SYNC_CONFLICT,
    OFFLINE_LIMIT_EXCEEDED,
    SYNC_OPERATION_REJECTED,       // Story 5.1 — sync handler rejected the operation
    SYNC_DUPLICATE_OPERATION,      // Story 5.1 — operationId already processed
    SYNC_UNKNOWN_OPERATION_TYPE,   // Story 5.1 — no handler registered for operationType
    MODULE_INACTIVE,               // Story 12.7 — the operation's module is not active for the tenant (sync push: REJECTED)
    MODULE_NOT_ENTITLED,           // Story 13.5 — HTTP 403: the module is not in the tenant's subscription (@RequiresEntitlement)
    MODULE_READ_ONLY,              // Story 13.5 — HTTP 403: subscription expired, read-only grace (writes refused; sync push: REJECTED)
    MODULE_NOT_FOUND,              // Story 13.8a — HTTP 404: unknown (or not tenant-assignable) module id
    MODULE_ACTIVATION_BLOCKED,     // Story 13.8a — HTTP 409: the activation plan is blocked (a step is not eligible)
    MODULE_HAS_DEPENDENTS,         // Story 13.8a — HTTP 409: active modules require the module being deactivated
    CORE_MODULE_REQUIRED,          // Story 13.8a — HTTP 409: the core module can never be deactivated
    MODULE_PROVISIONING_FAILED,    // Story 13.8a — HTTP 500: a module's tables could not be created; nothing changed
    MODULE_ACTIVATION_FAILED,      // Story 13.9 — HTTP 500: a module's activation hook failed; the call is rolled back
    MODULE_DEACTIVATION_FAILED,    // Story 13.9 — HTTP 500: a module's deactivation hook failed; the call is rolled back
    // ── Sync Gate (Story 5.4) ─────────────────────────────────────────────
    SYNC_REQUIRED,                 // HTTP 423 — device has not synced for >7 days
    DEVICE_ID_MISMATCH,            // HTTP 403 — deviceId belongs to a different user (v1s-13-2)
    // ── Subscription / Plan Limits ────────────────────────────────
    SUBSCRIPTION_NOT_FOUND, // HTTP 404 — no active subscription for tenant (Story 2.4)
    PLAN_LIMIT_EXCEEDED,    // HTTP 403 — store/product/employee limit reached on Free plan
    ACCOUNT_SUSPENDED,      // HTTP 403 — tenant status = SUSPENDED (manual admin lockout)
    ACCOUNT_DELETION_PENDING, // HTTP 403 — tenant status = DELETION_PENDING (Story 14.5)
    RATE_LIMIT_EXCEEDED,    // HTTP 429 — >100 req/min per tenant
    DELETION_ALREADY_REQUESTED, // HTTP 409 — deletion already pending (Story 14.5)
    DELETION_NOT_PENDING,       // HTTP 409 — no deletion pending to cancel (Story 14.5)
    INVALID_STATE,              // HTTP 409 — admin action not allowed in the tenant's current state (Story 9-2)
    // ── Audit ─────────────────────────────────────────────────────────────
    AUDIT_IMMUTABLE,        // HTTP 403 — attempt to modify or delete an immutable audit entry
    AUDIT_ENTRY_NOT_FOUND,  // HTTP 404 — audit entry id unknown in the tenant (v1s-18-6)
    // ── Export (Story v1s-18-1) ───────────────────────────────────────────
    PLAN_FEATURE_REQUIRED,     // HTTP 403 — feature needs Premium (details.feature)
    EXPORT_NOT_FOUND,          // HTTP 404 — export job id unknown in the tenant
    EXPORT_EXPIRED,            // HTTP 410 — export file expired / purged
    EXPORT_NOT_READY,          // HTTP 409 — job PENDING / RUNNING / FAILED
    EXPORT_DATASET_UNKNOWN,    // HTTP 400 — unknown dataset key
    EXPORT_FORMAT_UNSUPPORTED, // HTTP 400 — no renderer for the format yet
    EXPORT_LINK_INVALID,       // HTTP 403 — bad / tampered signed download token
    EXPORT_QUEUE_FULL,         // HTTP 429 — export executor saturated
    EXPORT_PDF_TOO_LARGE,      // HTTP 422 — PDF over keevo.export.pdf-max-rows / pdf-max-closures (v1s-18-3)
    // ── WhatsApp (Story 8.0) ──────────────────────────────────────────────
    WHATSAPP_DELIVERY_FAILED, // WhatsApp message delivery failed (Wassender API error or timeout)
    // ── Invoicing (Story 14.1) ────────────────────────────────────────────
    INVOICE_NOT_FOUND,          // HTTP 404 — invoice id unknown in the tenant
    INVOICE_NOT_EDITABLE,       // HTTP 409 — only a DRAFT invoice can be modified or deleted
    INVOICE_INVALID_TRANSITION, // HTTP 409 — lifecycle transition not allowed from the invoice's status
    INVOICE_NOT_ISSUABLE,       // HTTP 422 — the draft misses a mandatory element (details.reason, French)
    // ── Invoice PDF / WhatsApp sending, company identity (Story 14.2) ─────
    INVOICE_PHONE_REQUIRED,     // HTTP 422 — no WhatsApp number given and the client has none
    INVOICE_PHONE_INVALID,      // HTTP 422 — the WhatsApp number is not a valid phone number
    INVOICE_LINK_INVALID,       // HTTP 404 — public invoice link tampered / unknown invoice / tenant not active
    INVOICE_LINK_RATE_LIMITED,  // HTTP 429 — public invoice link opened too often (per token, 10 min window)
    INVOICE_LINK_EXPIRED,       // HTTP 410 — public invoice link older than its validity (30 days)
    WHATSAPP_UNAVAILABLE,       // HTTP 503 — no WhatsApp provider configured (NoOp): nothing sent, nothing recorded
    COMPANY_LOGO_INVALID,       // HTTP 422 — not a PNG / JPEG image (or corrupt)
    COMPANY_LOGO_TOO_LARGE,     // HTTP 422 — logo over keevo.company-logo.max-bytes
    COMPANY_LOGO_TOO_HEAVY,     // HTTP 422 — re-encoded logo still over 300 KB (inlined in every PDF)
    COMPANY_LOGO_NOT_FOUND,     // HTTP 404 — the company has no logo
    // ── Generic ───────────────────────────────────────────────────
    NOT_FOUND,
    VALIDATION_FAILED,
    VALIDATION_ERROR,
    INTERNAL_ERROR,
    SERVICE_UNAVAILABLE,        // HTTP 503 — a dependency (database read) failed, nothing was stored; retry later
    /**
     * FORBIDDEN — authenticated user does not have the required role.
     * Maps to HTTP 403. Use instead of UNAUTHORIZED (401) when user IS
     * authenticated but lacks permission (H2 fix — role guards).
     */
    FORBIDDEN;              // HTTP 403 — wrong role (authenticated but not authorised)
}
