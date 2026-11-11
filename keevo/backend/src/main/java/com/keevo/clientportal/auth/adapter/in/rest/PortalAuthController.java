package com.keevo.clientportal.auth.adapter.in.rest;

import com.keevo.clientportal.auth.application.PortalAuthService;
import com.keevo.shared.infrastructure.web.ApiResponseWrapper;
import com.keevo.shared.infrastructure.web.ClientIps;
import com.keevo.shared.module.EntitlementExempt;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

/**
 * Story 21.7 — sign-in of the client portal (« retrouver mes factures »), JWT-free ({@code SecurityConfig.PUBLIC_PATHS}
 * {@code /api/v1/portal/**}): an end client asks for a code on his phone, sends it back and gets a portal session
 * token ({@code kvc_…}, 30 days) for {@code GET /api/v1/portal/invoices}. Merchant accounts are never involved.
 */
@Tag(name = "Client portal", description = "End clients find their invoices by phone (Story 21.7)")
@EntitlementExempt("portail client : clients finaux hors compte commerçant, session propre")
@RestController
@RequestMapping("/api/v1/portal/auth")
public class PortalAuthController {

    /** {@code POST …/otp/request}. */
    public record CodeRequest(@NotBlank @Size(max = 40) String phone) {
    }

    /** {@code POST …/otp/verify}. */
    public record CodeCheck(@NotBlank @Size(max = 40) String phone, @NotBlank @Size(max = 16) String code) {
    }

    /** Validity of the code sent, in seconds. */
    public record CodeSent(int expiresIn) {
    }

    /** The portal session: the bearer token and when it stops working. */
    public record SessionDto(String accessToken, Instant expiresAt) {
    }

    private final PortalAuthService auth;

    public PortalAuthController(PortalAuthService auth) {
        this.auth = auth;
    }

    @Operation(summary = "Send a portal sign-in code to a phone",
            description = "SMS (and WhatsApp when configured); same answer whether the phone has invoices or not. "
                    + "422 PHONE_COUNTRY_NOT_SUPPORTED / VALIDATION_FAILED, 422 VALIDATION_ERROR (field missing), "
                    + "429 OTP_TOO_MANY_REQUESTS.")
    @SecurityRequirements
    @PostMapping("/otp/request")
    public ResponseEntity<ApiResponseWrapper<CodeSent>> requestCode(
            @Valid @RequestBody CodeRequest body,
            @RequestHeader(value = HttpHeaders.ACCEPT_LANGUAGE, required = false) String language,
            HttpServletRequest request) {
        int expiresIn = auth.requestCode(body.phone(), ClientIps.of(request), language);
        return ResponseEntity.ok(ApiResponseWrapper.ok(new CodeSent(expiresIn)));
    }

    @Operation(summary = "Check the portal code and open a portal session",
            description = "401 OTP_INVALID (wrong, used or expired code), 429 OTP_TOO_MANY_ATTEMPTS / "
                    + "OTP_TOO_MANY_REQUESTS, 422 PHONE_COUNTRY_NOT_SUPPORTED / VALIDATION_FAILED (not a phone), "
                    + "422 VALIDATION_ERROR (field missing). A merchant sign-in code is refused here.")
    @SecurityRequirements
    @PostMapping("/otp/verify")
    public ResponseEntity<ApiResponseWrapper<SessionDto>> verifyCode(@Valid @RequestBody CodeCheck body,
                                                                     HttpServletRequest request) {
        PortalAuthService.Session session = auth.signIn(body.phone(), body.code(), ClientIps.of(request));
        return ResponseEntity.ok(ApiResponseWrapper.ok(new SessionDto(session.accessToken(), session.expiresAt())));
    }

    @Operation(summary = "Sign out of the portal", description = "Deletes the session; 204 even when unknown.")
    @SecurityRequirements
    @PostMapping("/logout")
    public ResponseEntity<Void> logout(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            HttpServletRequest request) {
        auth.signOut(authorization, ClientIps.of(request));
        return ResponseEntity.noContent().build();
    }
}
