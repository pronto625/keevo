package com.keevo.identity.auth.adapter.in.rest;

import com.keevo.identity.auth.application.service.AccountDeletionService;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import com.keevo.shared.infrastructure.web.ApiResponseWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

/**
 * AccountController — Self-service account management endpoints (Story 14.5).
 *
 * <p>Exposes OWNER-only endpoints for requesting/cancelling account deletion
 * (FR91 — RGPD suppression de compte).
 * The {@code /api/v1/account/**} prefix is protected by the JWT filter chain
 * (requires authenticated user), and OWNER-only gating is enforced via
 * {@code @PreAuthorize} + programmatic {@code isOwnerRole()} check.
 */
@RestController
@RequestMapping("/api/v1/account")
public class AccountController {

    private static final Logger log = LoggerFactory.getLogger(AccountController.class);

    private final AccountDeletionService accountDeletionService;

    public AccountController(AccountDeletionService accountDeletionService) {
        this.accountDeletionService = accountDeletionService;
    }

    @PostMapping("/delete")
    @PreAuthorize("hasRole('OWNER')")
    public ResponseEntity<ApiResponseWrapper<Map<String, String>>> requestDeletion() {
        requireOwnerRole();
        UUID actorId = extractActorId();
        String tenantId = extractTenantId();

        accountDeletionService.requestDeletion(actorId, tenantId);

        return ResponseEntity.ok(ApiResponseWrapper.ok(
                Map.of("message", "Demande de suppression enregistrée — vos données seront supprimées dans 30 jours")));
    }

    @PostMapping("/delete/cancel")
    @PreAuthorize("hasRole('OWNER')")
    public ResponseEntity<ApiResponseWrapper<Map<String, String>>> cancelDeletion() {
        requireOwnerRole();
        UUID actorId = extractActorId();
        String tenantId = extractTenantId();

        accountDeletionService.cancelDeletion(actorId, tenantId);

        return ResponseEntity.ok(ApiResponseWrapper.ok(
                Map.of("message", "Demande de suppression annulée — votre compte est de nouveau actif")));
    }

    // ── Security helpers (mirror DayClosureController / StoreController) ──────

    private void requireOwnerRole() {
        if (!isOwnerRole()) {
            throw new DomainException(ErrorCode.FORBIDDEN,
                    "Seul le propriétaire peut gérer la suppression du compte");
        }
    }

    private boolean isOwnerRole() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.getAuthorities().stream()
                .anyMatch(a -> "ROLE_OWNER".equals(a.getAuthority()));
    }

    private UUID extractActorId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || auth.getPrincipal() == null) {
            throw new DomainException(ErrorCode.FORBIDDEN, "No authenticated user found");
        }
        return (UUID) auth.getPrincipal();
    }

    private String extractTenantId() {
        return com.keevo.shared.infrastructure.persistence.TenantContext.getCurrentTenant();
    }
}
