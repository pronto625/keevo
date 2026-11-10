package com.keevo.whatsappcloud.account.adapter.in.rest;

import com.keevo.shared.infrastructure.persistence.TenantContext;
import com.keevo.shared.infrastructure.security.CurrentActor;
import com.keevo.shared.infrastructure.web.ApiResponseWrapper;
import com.keevo.shared.module.EntitlementExempt;
import com.keevo.shared.module.RequiresEntitlement;
import com.keevo.whatsappcloud.account.application.WhatsAppAccountService;
import com.keevo.whatsappcloud.module.WhatsAppCloudModule;
import com.keevo.whatsappcloud.template.application.WhatsAppTemplateService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

/**
 * Story 21.5 — the company's WhatsApp Business number (module whatsapp-cloud): every member reads whether it is
 * connected (the app offers « WhatsApp » for invoices from it — Bref {@code GET /whatsapp/shops/:id/status}); the owner
 * connects it with the result of Meta's embedded signup and disconnects it. The access token never leaves the server.
 */
@RestController
@RequestMapping("/api/v1/whatsapp/account")
public class WhatsAppAccountController {

    private static final Logger log = LoggerFactory.getLogger(WhatsAppAccountController.class);

    /** Meta's embedded signup result: the one-time code, the WABA id and the number id chosen by the owner. */
    public record ConnectRequest(
            @NotBlank(message = "La connexion WhatsApp a échoué : recommencez depuis l'application")
            @Size(max = 2048, message = "La connexion WhatsApp a échoué : recommencez depuis l'application") String code,
            @NotBlank(message = "La connexion WhatsApp a échoué : recommencez depuis l'application")
            @Size(max = 64, message = "La connexion WhatsApp a échoué : recommencez depuis l'application") String wabaId,
            @NotBlank(message = "La connexion WhatsApp a échoué : recommencez depuis l'application")
            @Size(max = 64, message = "La connexion WhatsApp a échoué : recommencez depuis l'application")
            String phoneNumberId) {
    }

    public record AccountView(boolean connected, String displayPhoneNumber, String verifiedName, Instant connectedAt) {
        static AccountView of(WhatsAppAccountService.Status s) {
            return new AccountView(s.connected(), s.displayPhoneNumber(), s.verifiedName(), s.connectedAt());
        }
    }

    private final WhatsAppAccountService accounts;
    private final WhatsAppTemplateService templates;

    public WhatsAppAccountController(WhatsAppAccountService accounts, WhatsAppTemplateService templates) {
        this.accounts = accounts;
        this.templates = templates;
    }

    /** GET /api/v1/whatsapp/account — {@code {connected, displayPhoneNumber, verifiedName, connectedAt}}. */
    @PreAuthorize("hasAnyRole('OWNER','MANAGER','EMPLOYEE','VIEWER')")
    @RequiresEntitlement(value = WhatsAppCloudModule.ID, access = RequiresEntitlement.Access.READ)
    @GetMapping
    public ResponseEntity<ApiResponseWrapper<AccountView>> status() {
        return ResponseEntity.ok(ApiResponseWrapper.ok(AccountView.of(accounts.status(TenantContext.requireCurrent()))));
    }

    /**
     * POST /api/v1/whatsapp/account — connects the number (owner); the invoice template and the drafts are then
     * submitted to Meta in the background (best effort; a number moved to another WABA submits every template
     * again). 409 {@code WHATSAPP_NUMBER_ALREADY_CONNECTED}, 502
     * {@code WHATSAPP_CLOUD_META_ERROR}, 503 {@code WHATSAPP_CLOUD_UNAVAILABLE}.
     */
    @PreAuthorize("hasRole('OWNER')")
    @RequiresEntitlement(WhatsAppCloudModule.ID)
    @PostMapping
    public ResponseEntity<ApiResponseWrapper<AccountView>> connect(@Valid @RequestBody ConnectRequest request) {
        String schema = TenantContext.requireCurrent();
        WhatsAppAccountService.Connection connection = accounts.connect(schema, CurrentActor.actorId(),
                request.code(), request.wabaId(), request.phoneNumberId());
        try {
            if (connection.otherWaba()) {
                templates.resetForNewAccount(schema);
            }
        } catch (RuntimeException e) {
            log.warn("[WhatsApp Cloud] templates not reset after the connection of tenant {} ({})", schema,
                    e.getClass().getSimpleName());
        }
        templates.onConnectedAsync(schema);
        return ResponseEntity.ok(ApiResponseWrapper.ok(AccountView.of(connection.status())));
    }

    /** DELETE /api/v1/whatsapp/account — disconnects the number (owner, whatever the plan) → 204, idempotent. */
    @PreAuthorize("hasRole('OWNER')")
    @EntitlementExempt("déconnexion du WhatsApp Business — toujours possible pour le propriétaire, même sans le module")
    @DeleteMapping
    public ResponseEntity<Void> disconnect() {
        accounts.disconnect(TenantContext.requireCurrent(), CurrentActor.actorId());
        return ResponseEntity.noContent().build();
    }
}
