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
    USERNAME_ALREADY_EXISTS,    // Story 19.4 — HTTP 409: username taken (case-insensitive)
    INVALID_PHONE_NUMBER,
    INVALID_PASSWORD,
    INVALID_CREDENTIALS,
    ACCOUNT_LOCKED,
    TOKEN_EXPIRED,
    TOKEN_INVALID,              // Story 1.7 — loginToken used as accessToken, or invalid scope
    REFRESH_TOKEN_INVALID,
    UNAUTHORIZED,
    PHONE_COUNTRY_NOT_SUPPORTED, // Story 19.2 — HTTP 422: sign-in code requested for a phone outside the allowed countries
    OTP_INVALID,                 // Story 19.2 — HTTP 401: wrong, expired or already used sign-in code
    OTP_TOO_MANY_ATTEMPTS,       // Story 19.2 — HTTP 429: 5 wrong codes, the code is dead
    OTP_TOO_MANY_REQUESTS,       // Story 19.2 — HTTP 429: code request rate limit or daily failure cap
    PUSH_ENDPOINT_OWNED,         // Story 19.9b — HTTP 409: the browser push endpoint is linked to another account (other keys)
    COMPANY_ALREADY_EXISTS,      // Story 19.2 — HTTP 409: create-company by a user who already owns a company
    SOCIAL_TOKEN_INVALID,        // Story 19.3 — HTTP 401: Google / Apple ID token refused (signature, issuer, audience, expiry, email)
    APP_UPDATE_REQUIRED,         // Story 19.10 — HTTP 403: MANAGER / VIEWER session refused to a legacy (V1, no X-App-Version) app
    APP_VERSION_INVALID,         // Story 19.10 — HTTP 422: invalid super-admin app version edit (nothing saved)
    APP_VERSION_NOT_FOUND,       // Story 19.10 — HTTP 404: no app version row for this identity and platform

    // ── Tenant ────────────────────────────────────────────────────
    TENANT_NOT_FOUND,
    TENANT_ALREADY_EXISTS,
    TENANT_PROVISION_FAILED,

    // ── Membership (Story 1.7) ─────────────────────────────────────
    MEMBERSHIP_ALREADY_EXISTS,  // UNIQUE(user_id, tenant_id) violated
    MEMBERSHIP_NOT_FOUND,       // user is not a member of the requested tenant
    MEMBER_ALREADY_IN_COMPANY,  // Story 19.6 — HTTP 409: the added person already has a membership (active or not) here
    MEMBER_INACTIVE_IN_COMPANY, // Story 19.6 — HTTP 409: the added person has an inactive membership here (reactivate)
    MEMBER_ACCOUNT_SHARED,      // Story 19.6 — HTTP 409: account-wide change refused, the member's account is used elsewhere
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
    PRODUCT_MERGE_INVALID,       // HTTP 409 — Story 20.1: merge refused (already merged, archived target, stock left)
    PRODUCT_MERGED,              // HTTP 409 — Story 20.1: the product was merged into another (unarchive refused)
    // ── Inventory ─────────────────────────────────────────────────
    INSUFFICIENT_STOCK,
    STOCK_NOT_FOUND,
    STOCK_NOT_TRACKED,  // Story 17.12 — HTTP 422: stock operation on a product whose stock tracking is off

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
    SESSION_REVOKED,   // Story 12.2 — NFR12: token issued before tokens_valid_after cutoff (401); 16.11: device session closed
    DEVICE_SESSION_NOT_FOUND, // Story 16.11 — « Appareils connectés »: unknown or already closed session (404)
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
    TRANSFER_IN_TRANSIT,        // HTTP 409 — Story 17.12: stock tracking cannot be turned off while a transfer of the product is in transit

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
    MODULE_BACKFILL_RUNNING,       // Story 16.5 — HTTP 409: a tenant_modules backfill already holds the lock
    BUNDLE_NOT_FOUND,              // Story 16.2 — HTTP 404: unknown bundle id (« Pack introuvable »)
    BUNDLE_INVALID,                // Story 16.2 — HTTP 422: invalid bundle definition (nothing saved)
    BUNDLE_NOT_APPLICABLE,         // Story 16.2 — HTTP 422: the stored bundle holds a module no longer available; nothing written
    ADMIN_JOB_RUNNING,             // Story 16.4 — HTTP 409: a super-admin job of the same kind is already queued or running
    ADMIN_JOB_NOT_FOUND,           // Story 16.4 — HTTP 404: unknown super-admin job id (« Tâche introuvable »)
    MODULE_DISABLED_BY_OWNER,      // Story 19.13 — HTTP 403: the module is in the plan but the owner disabled it
    MODULE_NOT_IN_PLAN,            // Story 19.13 — HTTP 409: owner toggle of a module outside the plan (« Mon plan »)
    MODULE_NOT_TOGGLEABLE,         // Story 19.13 — HTTP 409: owner toggle of a module that cannot be disabled
    MODULE_REQUIREMENT_DISABLED,   // Story 19.13 — HTTP 409: owner activation while a required module is owner-disabled
    MODULE_ACTIVATION_RULE_INVALID, // Story 19.13 — HTTP 422: invalid super-admin activation rule set; nothing saved
    // ── Sync Gate (Story 5.4) ─────────────────────────────────────────────
    SYNC_REQUIRED,                 // HTTP 423 — device has not synced for >7 days
    DEVICE_ID_MISMATCH,            // HTTP 403 — deviceId belongs to a different user (v1s-13-2)
    // ── Subscription / Plan Limits ────────────────────────────────
    SUBSCRIPTION_NOT_FOUND, // HTTP 404 — no active subscription for tenant (Story 2.4)
    PLAN_LIMIT_EXCEEDED,    // HTTP 403 — store/product/employee limit reached on Free plan
    QUOTA_EXCEEDED,         // Story 19.8 — HTTP 403: a usage quota of the plan (documents, SMS, emails, AI credits) is used up
    PRODUCT_QUOTA_UNAVAILABLE, // Story 16.8 — HTTP 503: the product count could not be read for a finite limit (fail closed)
    PLAN_PROFILE_INVALID,   // Story 16.8 — HTTP 422: a super-admin plan profile edit is invalid; nothing saved
    PLAN_PROFILE_NOT_FOUND, // Story 16.14 — HTTP 404: unknown plan type on /admin/plan-profiles/{planType} (« Offre introuvable »)
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
    INVOICE_PAYMENT_DATE_INVALID, // HTTP 400 — Story 15.5: payment date in the future or before the issue date (details.reason)
    OVERPAYMENT,                // HTTP 409 — Story 20.4: payments + instalments would exceed what the invoice owes (details.remaining)
    // ── Invoice PDF / WhatsApp sending, company identity (Story 14.2) ─────
    INVOICE_PHONE_REQUIRED,     // HTTP 422 — no WhatsApp number given and the client has none
    INVOICE_PHONE_INVALID,      // HTTP 422 — the WhatsApp number is not a valid phone number
    INVOICE_LINK_INVALID,       // HTTP 404 — public invoice link tampered / unknown invoice / tenant not active
    INVOICE_LINK_RATE_LIMITED,  // HTTP 429 — public invoice link opened too often (per token, 10 min window)
    INVOICE_LINK_EXPIRED,       // HTTP 410 — public invoice link older than its validity (30 days)
    INVOICE_SIGNATURE_INVALID,  // HTTP 422 — Story 20.7: signature not a PNG / JPEG image (or corrupt)
    INVOICE_SIGNATURE_TOO_LARGE, // HTTP 422 — Story 20.7: upload over max-upload-bytes, or still over max-bytes re-encoded
    INVOICE_SIGNATURE_NOT_FOUND, // HTTP 404 — Story 20.7: the document has no such signature
    // ── Credit notes (Story 14.7) ─────────────────────────────────────────
    CREDIT_NOTE_NOT_FOUND,      // HTTP 404 — credit note id unknown in the tenant
    CREDIT_NOTE_INVALID,        // HTTP 422 — empty selection, unknown line, bad / excessive quantity (details.reason)
    WHATSAPP_UNAVAILABLE,       // HTTP 503 — no WhatsApp provider configured (NoOp): nothing sent, nothing recorded
    // ── Document sending by SMS / email (Story 20.8) ──────────────────────
    INVOICE_SMS_PHONE_REQUIRED, // HTTP 422 — SMS: no number given and the client has none
    INVOICE_SMS_PHONE_INVALID,  // HTTP 422 — SMS: the number is not a valid phone number
    INVOICE_EMAIL_REQUIRED,     // HTTP 422 — email: no address given and the client has none
    INVOICE_EMAIL_INVALID,      // HTTP 422 — email: the address is malformed
    SMS_UNAVAILABLE,            // HTTP 503 — no SMS provider configured: nothing sent, nothing counted
    EMAIL_UNAVAILABLE,          // HTTP 503 — no email provider configured: nothing sent, nothing counted
    SMS_DELIVERY_FAILED,        // HTTP 502 — every SMS provider failed: nothing recorded, nothing counted
    EMAIL_DELIVERY_FAILED,      // HTTP 502 — every email provider failed: nothing recorded, nothing counted
    INVOICE_SEND_UNCERTAIN,     // HTTP 409 — the provider did not answer (maybe delivered): not counted, never retried automatically
    PUBLIC_BASE_URL_MISSING,    // HTTP 503 — no absolute public base (keevo.public-base-url) for the link: nothing sent
    COMPANY_LOGO_INVALID,       // HTTP 422 — not a PNG / JPEG image (or corrupt)
    COMPANY_LOGO_TOO_LARGE,     // HTTP 422 — logo over keevo.company-logo.max-bytes
    COMPANY_LOGO_TOO_HEAVY,     // HTTP 422 — re-encoded logo still over 300 KB (inlined in every PDF)
    COMPANY_LOGO_NOT_FOUND,     // HTTP 404 — the company has no logo
    // ── Store logo (Story 19.12) ──────────────────────────────────────────
    STORE_LOGO_INVALID,         // HTTP 422 — not a PNG / JPEG image (or corrupt)
    STORE_LOGO_TOO_LARGE,       // HTTP 422 — logo over keevo.store-logo.max-bytes
    STORE_LOGO_TOO_HEAVY,       // HTTP 422 — re-encoded logo still over keevo.store-logo.max-bytes
    STORE_LOGO_NOT_FOUND,       // HTTP 404 — the store has no logo
    // ── Expenses (Story 20.10) ────────────────────────────────────────────
    EXPENSE_NOT_FOUND,          // HTTP 404 — expense unknown, deleted or of a store the actor does not see
    // ── Notification inbox (Story 19.9) ───────────────────────────────────
    NOTIFICATION_NOT_FOUND,     // HTTP 404 — notification unknown or of another user
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
