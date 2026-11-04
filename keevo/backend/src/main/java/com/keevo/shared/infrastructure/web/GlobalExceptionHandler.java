package com.keevo.shared.infrastructure.web;

import com.keevo.shared.domain.exception.DomainException;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authorization.AuthorizationDeniedException;
import org.springframework.validation.FieldError;
import org.springframework.validation.ObjectError;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * GlobalExceptionHandler — Translates domain and validation exceptions to API responses.
 *
 * <p>Response format:
 * <pre>{@code
 * {
 *   "error": "Human readable message",
 *   "code": "HTTP_STATUS_NAME",
 *   "domainCode": "PRODUCT_NOT_FOUND",
 *   "details": { ... },
 *   "timestamp": "2026-03-02T00:00:00Z"
 * }
 * }</pre>
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);


    /**
     * Story 11.2 — {@code keevo_errors_total{domainCode}}: every handler counts the {@code domainCode}
     * it returns (domain errors, validation, 403/404/405/409 and the catch-all INTERNAL_ERROR 500).
     */
    static final String ERRORS_METRIC = "keevo.errors";
    static final String UNKNOWN_DOMAIN_CODE = "UNKNOWN";

    /** Absent in slice tests and {@code new GlobalExceptionHandler()} — the counter is then skipped. */
    private final MeterRegistry meterRegistry;

    public GlobalExceptionHandler() {
        this.meterRegistry = null;
    }

    @Autowired
    public GlobalExceptionHandler(ObjectProvider<MeterRegistry> meterRegistry) {
        this.meterRegistry = meterRegistry.getIfAvailable();
    }

    @ExceptionHandler(DomainException.class)
    public ResponseEntity<ApiResponseWrapper<Void>> handleDomainException(DomainException ex) {
        countDomainError(ex.getDomainCode());
        HttpStatus status = domainCodeToHttpStatus(ex.getDomainCode());
        String userMessage = ErrorMessages.userMessage(ex.getDomainCode(), ex.getUserMessage(), ex.getMessage(),
                ex.getDetails());
        return ResponseEntity.status(status)
                .body(ApiResponseWrapper.error(
                        userMessage,
                        status.name(),
                        ex.getDomainCode(),
                        ex.getDetails()
                ));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponseWrapper<Void>> handleValidation(
            MethodArgumentNotValidException ex) {
        // Story 16.14 — field → French message (custom annotation messages, else Hibernate Validator's French
        // bundle: spring.web.locale = fr); the response's `error` is the first field's message. The validator reports
        // violations in no fixed order: sorted by field name, then constraint code, so the answer is deterministic.
        Map<String, String> fieldErrors = new LinkedHashMap<>();
        List<FieldError> sorted = ex.getBindingResult().getFieldErrors().stream()
                .sorted(Comparator.comparing(FieldError::getField)
                        .thenComparing(fe -> fe.getCode() == null ? "" : fe.getCode()))
                .toList();
        for (FieldError fe : sorted) {
            fieldErrors.putIfAbsent(fe.getField(), fe.getDefaultMessage());
        }
        String error = fieldErrors.values().stream().filter(m -> m != null && !m.isBlank()).findFirst()
                .orElseGet(() -> ex.getBindingResult().getGlobalErrors().stream()
                        .map(ObjectError::getDefaultMessage)
                        .filter(m -> m != null && !m.isBlank())
                        .findFirst()
                        .orElse(ErrorMessages.of("VALIDATION_ERROR", "Données invalides")));
        countDomainError("VALIDATION_ERROR");
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                .body(ApiResponseWrapper.error(
                        error,
                        HttpStatus.UNPROCESSABLE_ENTITY.name(),
                        "VALIDATION_ERROR",
                        fieldErrors
                ));
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiResponseWrapper<Void>> handleTypeMismatch(
            MethodArgumentTypeMismatchException ex) {
        countDomainError("VALIDATION_ERROR");
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiResponseWrapper.error(
                        "Paramètre invalide : " + ex.getName(),
                        HttpStatus.BAD_REQUEST.name(),
                        "VALIDATION_ERROR",
                        null
                ));
    }

    @ExceptionHandler(InvalidRequestParameterException.class)
    public ResponseEntity<ApiResponseWrapper<Void>> handleInvalidParameter(
            InvalidRequestParameterException ex) {
        countDomainError("VALIDATION_ERROR");
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiResponseWrapper.error(
                        ex.getUserMessage() != null ? ex.getUserMessage()
                                : "Paramètre invalide : " + ex.getParameter(),
                        HttpStatus.BAD_REQUEST.name(),
                        "VALIDATION_ERROR",
                        null
                ));
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ApiResponseWrapper<Void>> handleMissingParam(
            MissingServletRequestParameterException ex) {
        countDomainError("MISSING_PARAMETER");
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiResponseWrapper.error(
                        "Paramètre requis manquant : " + ex.getParameterName(),
                        HttpStatus.BAD_REQUEST.name(),
                        "MISSING_PARAMETER",
                        null
                ));
    }

    @ExceptionHandler(AuthorizationDeniedException.class)
    public ResponseEntity<ApiResponseWrapper<Void>> handleAccessDenied(AuthorizationDeniedException ex) {
        countDomainError("FORBIDDEN");
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(ApiResponseWrapper.error(
                        ErrorMessages.of("FORBIDDEN", "Vous n'avez pas les droits nécessaires"),
                        HttpStatus.FORBIDDEN.name(),
                        "FORBIDDEN",
                        null
                ));
    }

    /** Story 16.5 — the plan was changed but the module rows could not follow: rolled back, retry. */
    static final String PLAN_MODULE_ROWS_UNAVAILABLE =
            "Les modules du compte n'ont pas pu être mis à jour. Réessayez dans quelques instants.";

    @ExceptionHandler(com.keevo.shared.module.application.port.PlanModuleRowSync.PlanUnreadableException.class)
    public ResponseEntity<ApiResponseWrapper<Void>> handlePlanModuleRowsUnavailable(RuntimeException ex) {
        log.warn("PLAN_MODULE_ROWS_UNAVAILABLE: {}", ex.getMessage());
        countDomainError("SERVICE_UNAVAILABLE");
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(ApiResponseWrapper.error(PLAN_MODULE_ROWS_UNAVAILABLE, HttpStatus.SERVICE_UNAVAILABLE.name(),
                        "SERVICE_UNAVAILABLE", null));
    }

    @ExceptionHandler(OptimisticLockingFailureException.class)
    public ResponseEntity<ApiResponseWrapper<Void>> handleOptimisticLock(
            OptimisticLockingFailureException ex) {
        log.warn("OPTIMISTIC_LOCK: {}", ex.getMessage());
        countDomainError("OPTIMISTIC_LOCK");
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiResponseWrapper.error(
                        ErrorMessages.of("OPTIMISTIC_LOCK", "Conflit de version"),
                        HttpStatus.CONFLICT.name(),
                        "OPTIMISTIC_LOCK",
                        null
                ));
    }

    /** Unknown route → 404 (was 500 INTERNAL_ERROR with a full stack trace in the logs). */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiResponseWrapper<Void>> handleNoResource(NoResourceFoundException ex) {
        log.debug("NO_ROUTE: {}", ex.getResourcePath());
        countDomainError("NOT_FOUND");
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ApiResponseWrapper.error(
                        ErrorMessages.of("NOT_FOUND", "Ressource introuvable"),
                        HttpStatus.NOT_FOUND.name(),
                        "NOT_FOUND",
                        null
                ));
    }

    /** Known route, wrong HTTP method → 405 (was 500). */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiResponseWrapper<Void>> handleMethodNotSupported(
            HttpRequestMethodNotSupportedException ex) {
        log.debug("METHOD_NOT_ALLOWED: {}", ex.getMessage());
        countDomainError("METHOD_NOT_ALLOWED");
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED)
                .body(ApiResponseWrapper.error(
                        "Méthode non autorisée",
                        HttpStatus.METHOD_NOT_ALLOWED.name(),
                        "METHOD_NOT_ALLOWED",
                        null
                ));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponseWrapper<Void>> handleGeneral(Exception ex) {
        log.error("Unhandled exception — {}: {}", ex.getClass().getSimpleName(), ex.getMessage(), ex);
        countDomainError("INTERNAL_ERROR");
        String userMessage = ErrorMessages.of("INTERNAL_ERROR", "Une erreur inattendue s'est produite");
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponseWrapper.error(
                        userMessage,
                        HttpStatus.INTERNAL_SERVER_ERROR.name(),
                        "INTERNAL_ERROR",
                        null
                ));
    }

    /** Kept for the existing callers/tests: see {@link ErrorMessages#userMessage}. */
    static String userMessage(String domainCode, String fallback, Map<String, Object> details) {
        return ErrorMessages.userMessage(domainCode, null, fallback, details);
    }

    /** See {@link ErrorMessages#withDetails}. */
    static String withDetails(String message, Map<String, Object> details) {
        return ErrorMessages.withDetails(message, details);
    }

    private HttpStatus domainCodeToHttpStatus(String domainCode) {
        if (domainCode == null) return HttpStatus.INTERNAL_SERVER_ERROR;
        return switch (domainCode) {
            case "USER_NOT_FOUND", "TENANT_NOT_FOUND",
                 "PRODUCT_NOT_FOUND", "CATEGORY_NOT_FOUND",
                 "PRODUCT_PHOTO_NOT_FOUND",
                 "CLIENT_NOT_FOUND", "SUPPLIER_NOT_FOUND",
                 "STOCK_NOT_FOUND", "STORE_NOT_FOUND",
                 "EMPLOYEE_NOT_FOUND",
                 "DEVICE_SESSION_NOT_FOUND",
                 "MEMBERSHIP_NOT_FOUND",
                 "SUBSCRIPTION_NOT_FOUND",
                 "SALE_NOT_FOUND",
                 "INVENTORY_SESSION_NOT_FOUND",
                 "INVENTORY_STORE_NOT_FOUND",
                 "REPORT_NOT_FOUND",
                 "AUDIT_ENTRY_NOT_FOUND",
                 "EXPORT_NOT_FOUND",
                 "MODULE_NOT_FOUND",
                 "BUNDLE_NOT_FOUND",
                 "ADMIN_JOB_NOT_FOUND",
                 "PLAN_PROFILE_NOT_FOUND",
                 "INVOICE_NOT_FOUND",
                 "CREDIT_NOTE_NOT_FOUND",
                 "INVOICE_LINK_INVALID",
                 "COMPANY_LOGO_NOT_FOUND",
                 "NOTIFICATION_NOT_FOUND",
                 "NOT_FOUND" -> HttpStatus.NOT_FOUND;
            case "UNAUTHORIZED", "TOKEN_EXPIRED",
                 "TOKEN_INVALID",
                 "INVALID_CREDENTIALS", "ACCOUNT_LOCKED",
                 "REFRESH_TOKEN_INVALID",
                 "ACCOUNT_INACTIVE",
                 "STORE_REASSIGNED",
                 "SESSION_REVOKED" -> HttpStatus.UNAUTHORIZED;
            case "EMAIL_ALREADY_EXISTS", "USER_ALREADY_EXISTS",
                 "TENANT_ALREADY_EXISTS",
                 "MEMBERSHIP_ALREADY_EXISTS",
                 "PRODUCT_NAME_ALREADY_EXISTS",
                 "WAREHOUSE_ALREADY_EXISTS",
                 "SALE_ALREADY_EXISTS",
                 "DAY_ALREADY_CLOSED",
                 "INVENTORY_SESSION_ALREADY_ACTIVE",
                 "INVENTORY_SESSION_NOT_IN_PROGRESS",
                 "REPORT_ALREADY_SENT",
                 "SALE_ALREADY_CANCELLED",
                 "OPTIMISTIC_LOCK",
                 "PHONE_ALREADY_REGISTERED",
                 "DELETION_ALREADY_REQUESTED",
                 "DELETION_NOT_PENDING",
                 "INVALID_STATE",
                 "MODULE_ACTIVATION_BLOCKED",
                 "MODULE_HAS_DEPENDENTS",
                 "CORE_MODULE_REQUIRED",
                 "MODULE_BACKFILL_RUNNING",
                 "ADMIN_JOB_RUNNING",
                 "TRANSFER_IN_TRANSIT",
                 "INVOICE_NOT_EDITABLE",
                 "INVOICE_INVALID_TRANSITION",
                 "EXPORT_NOT_READY" -> HttpStatus.CONFLICT;  // Story 2.4 / 3.1 / 4.1 / 4.4 / 6.1 / 7.2 / v1s-13-1 / v1s-13-5 / v1s-14-11 / 14.5
            case "VALIDATION_ERROR", "INVALID_AMOUNT",
                 "INVALID_PHONE_NUMBER", "INVALID_PASSWORD",
                 "INSUFFICIENT_STOCK",
                 "STOCK_NOT_TRACKED",
                 "STORE_NOT_ACTIVE",
                 "VALIDATION_FAILED",
                 "SAME_SOURCE_DESTINATION",
                 "DISCOUNT_EXCEEDS_SUBTOTAL",
                 "SALE_NOT_PENDING",
                 "SALE_NOT_COMPLETED",
                 "INVALID_OR_EXPIRED_CODE",
                 "CODE_LOCKED",
                 "INVOICE_NOT_ISSUABLE",
                 "CREDIT_NOTE_INVALID",
                 "INVOICE_PHONE_REQUIRED",
                 "INVOICE_PHONE_INVALID",
                 "COMPANY_LOGO_INVALID",
                 "COMPANY_LOGO_TOO_LARGE",
                 "COMPANY_LOGO_TOO_HEAVY",
                 "PLAN_PROFILE_INVALID",
                 "BUNDLE_INVALID",
                 "BUNDLE_NOT_APPLICABLE",
                 "EXPORT_PDF_TOO_LARGE" -> HttpStatus.UNPROCESSABLE_ENTITY;  // v1s-18-3 — PDF over its row limit
            case "SERVICE_UNAVAILABLE",
                 "PRODUCT_QUOTA_UNAVAILABLE",
                 "WHATSAPP_UNAVAILABLE" -> HttpStatus.SERVICE_UNAVAILABLE;
            case "PRODUCT_PHOTO_TOO_LARGE" -> HttpStatus.PAYLOAD_TOO_LARGE;
            case "PRODUCT_PHOTO_INVALID" -> HttpStatus.UNSUPPORTED_MEDIA_TYPE;
            case "JUSTIFICATION_REQUIRED",
                 "JUSTIFICATION_TOO_SHORT",
                 "INVOICE_PAYMENT_DATE_INVALID" -> HttpStatus.BAD_REQUEST;  // 15.5 — back-dated payment out of range
            case "SECTOR_TEMPLATE_NOT_FOUND",
                 "ONBOARDING_ALREADY_COMPLETED",
                 "INVENTORY_INVALID_CATEGORIES",
                 "INVENTORY_COUNT_PRODUCT_NOT_IN_SCOPE",
                 "EXPORT_DATASET_UNKNOWN",
                 "EXPORT_FORMAT_UNSUPPORTED" -> HttpStatus.BAD_REQUEST;
            case "EXPORT_EXPIRED",
                 "INVOICE_LINK_EXPIRED" -> HttpStatus.GONE;  // v1s-18-1 — expired export file
            case "TENANT_PROVISION_FAILED",
                 "MODULE_PROVISIONING_FAILED",
                 "MODULE_ACTIVATION_FAILED",
                 "MODULE_DEACTIVATION_FAILED" -> HttpStatus.INTERNAL_SERVER_ERROR;
            case "CSV_PARSE_ERROR" -> HttpStatus.UNPROCESSABLE_ENTITY;  // Story 2.4 malformed CSV
            case "PLAN_LIMIT_EXCEEDED",
                 "QUOTA_EXCEEDED",
                 "ACCOUNT_SUSPENDED",
                 "ACCOUNT_DELETION_PENDING",
                 "AUDIT_IMMUTABLE",
                 "PASSWORD_CHANGE_REQUIRED",
                 "FORBIDDEN",
                 "DEVICE_ID_MISMATCH",
                 "MODULE_INACTIVE",
                 "MODULE_NOT_ENTITLED",
                 "MODULE_READ_ONLY",
                 "CANNOT_CHANGE_OWN_ROLE",
                 "CANNOT_DEMOTE_LAST_OWNER",
                 "CANNOT_SET_OWN_PASSWORD",
                 "PLAN_FEATURE_REQUIRED",
                 "EXPORT_LINK_INVALID" -> HttpStatus.FORBIDDEN;   // H2 fix: role mismatch is 403, not 401; AUDIT_IMMUTABLE = immutable log; DEVICE_ID_MISMATCH = deviceId belongs to another user (v1s-13-2); CANNOT_* = role anti-lockout (v1s-14-11)
            case "RATE_LIMIT_EXCEEDED",
                 "INVOICE_LINK_RATE_LIMITED",
                 "EXPORT_QUEUE_FULL" -> HttpStatus.TOO_MANY_REQUESTS;
            case "WHATSAPP_DELIVERY_FAILED" -> HttpStatus.BAD_GATEWAY;  // upstream WhatsApp failure
            default -> HttpStatus.INTERNAL_SERVER_ERROR;
        };
    }

    /** Bounded tag: {@code domainCode} values come from the {@code ErrorCode} set; null → UNKNOWN. */
    private void countDomainError(String domainCode) {
        if (meterRegistry == null) {
            return;
        }
        try {
            Counter.builder(ERRORS_METRIC)
                    .description("Domain errors mapped to an API response, by domainCode")
                    .tag("domainCode", domainCode == null || domainCode.isBlank() ? UNKNOWN_DOMAIN_CODE : domainCode)
                    .register(meterRegistry)
                    .increment();
        } catch (RuntimeException e) {
            // a metrics failure must never change the error response
            log.warn("METRICS: domain error not counted: {}", e.getMessage());
        }
    }
}
