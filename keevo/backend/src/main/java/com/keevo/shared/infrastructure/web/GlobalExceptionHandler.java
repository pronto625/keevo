package com.keevo.shared.infrastructure.web;

import com.keevo.shared.domain.exception.DomainException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;
import java.util.HashMap;
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
     * French user-facing error messages mapped by domain code.
     * These are shown in the {@code error} field of the API response.
     * NEVER expose technical details — all messages are human-readable French.
     */
    private static final Map<String, String> FR_MESSAGES = Map.ofEntries(
            Map.entry("USER_NOT_FOUND",           "Utilisateur introuvable"),
            Map.entry("USER_ALREADY_EXISTS",       "Ce numéro est déjà associé à un compte"),
            Map.entry("INVALID_CREDENTIALS",       "Téléphone ou mot de passe incorrect"),
            Map.entry("ACCOUNT_LOCKED",            "Compte temporairement bloqué, réessayez dans 15 minutes"),
            Map.entry("TOKEN_EXPIRED",             "Session expirée, veuillez vous reconnecter"),
            Map.entry("TOKEN_INVALID",             "Jeton d'authentification invalide"),
            Map.entry("UNAUTHORIZED",              "Accès non autorisé"),
            Map.entry("FORBIDDEN",                 "Vous n'avez pas les droits nécessaires"),
            Map.entry("TENANT_NOT_FOUND",          "Espace de travail introuvable"),
            Map.entry("PLAN_LIMIT_EXCEEDED",       "Limite de votre plan atteinte"),
            Map.entry("RATE_LIMIT_EXCEEDED",       "Trop de requêtes, veuillez patienter"),
            Map.entry("AUDIT_IMMUTABLE",           "Les entrées du journal d'audit ne peuvent pas être modifiées"),
            Map.entry("INSUFFICIENT_STOCK",        "Stock insuffisant pour cette opération"),
            Map.entry("STOCK_NOT_FOUND",           "Niveau de stock introuvable"),
            Map.entry("VALIDATION_ERROR",          "Données invalides"),
            Map.entry("INTERNAL_ERROR",            "Une erreur inattendue s'est produite")
    );

    @ExceptionHandler(DomainException.class)
    public ResponseEntity<ApiResponseWrapper<Void>> handleDomainException(DomainException ex) {
        HttpStatus status = domainCodeToHttpStatus(ex.getDomainCode());
        String userMessage = FR_MESSAGES.getOrDefault(ex.getDomainCode(), ex.getMessage());
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
        Map<String, String> fieldErrors = new HashMap<>();
        for (FieldError fe : ex.getBindingResult().getFieldErrors()) {
            fieldErrors.put(fe.getField(), fe.getDefaultMessage());
        }
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                .body(ApiResponseWrapper.error(
                        "Validation failed",
                        HttpStatus.UNPROCESSABLE_ENTITY.name(),
                        "VALIDATION_ERROR",
                        fieldErrors
                ));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponseWrapper<Void>> handleGeneral(Exception ex) {
        log.error("Unhandled exception — {}: {}", ex.getClass().getSimpleName(), ex.getMessage(), ex);
        String userMessage = FR_MESSAGES.getOrDefault("INTERNAL_ERROR", "Une erreur inattendue s'est produite");
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponseWrapper.error(
                        userMessage,
                        HttpStatus.INTERNAL_SERVER_ERROR.name(),
                        "INTERNAL_ERROR",
                        null
                ));
    }

    private HttpStatus domainCodeToHttpStatus(String domainCode) {
        if (domainCode == null) return HttpStatus.INTERNAL_SERVER_ERROR;
        return switch (domainCode) {
            case "USER_NOT_FOUND", "TENANT_NOT_FOUND",
                 "PRODUCT_NOT_FOUND", "CATEGORY_NOT_FOUND",
                 "STOCK_NOT_FOUND", "STORE_NOT_FOUND",
                 "MEMBERSHIP_NOT_FOUND",
                 "NOT_FOUND" -> HttpStatus.NOT_FOUND;
            case "UNAUTHORIZED", "TOKEN_EXPIRED",
                 "TOKEN_INVALID",
                 "INVALID_CREDENTIALS", "ACCOUNT_LOCKED",
                 "REFRESH_TOKEN_INVALID" -> HttpStatus.UNAUTHORIZED;
            case "EMAIL_ALREADY_EXISTS", "USER_ALREADY_EXISTS",
                 "TENANT_ALREADY_EXISTS",
                 "MEMBERSHIP_ALREADY_EXISTS" -> HttpStatus.CONFLICT;
            case "VALIDATION_ERROR", "INVALID_AMOUNT",
                 "INVALID_PHONE_NUMBER", "INVALID_PASSWORD",
                 "INSUFFICIENT_STOCK" -> HttpStatus.UNPROCESSABLE_ENTITY;
            case "SECTOR_TEMPLATE_NOT_FOUND",
                 "ONBOARDING_ALREADY_COMPLETED" -> HttpStatus.BAD_REQUEST;
            case "TENANT_PROVISION_FAILED" -> HttpStatus.INTERNAL_SERVER_ERROR;
            case "PLAN_LIMIT_EXCEEDED",
                 "ACCOUNT_SUSPENDED",
                 "AUDIT_IMMUTABLE",
                 "FORBIDDEN" -> HttpStatus.FORBIDDEN;   // H2 fix: role mismatch is 403, not 401; AUDIT_IMMUTABLE = immutable log
            case "RATE_LIMIT_EXCEEDED" -> HttpStatus.TOO_MANY_REQUESTS;
            default -> HttpStatus.INTERNAL_SERVER_ERROR;
        };
    }
}
