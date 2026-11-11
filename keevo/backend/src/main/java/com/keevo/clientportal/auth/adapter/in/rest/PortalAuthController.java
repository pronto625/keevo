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
import org.springframework.http.HttpStatus;
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
 * token ({@code kvc_…}, 24 hours like Bref's client token) for {@code GET /api/v1/portal/invoices}. Reprise
 * 11/11/2026: Bref's client account too — {@code register} (the code once + name + password + optional e-mail) and
 * {@code login} (phone or e-mail + password, no SMS). Merchant accounts are never involved.
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

    /**
     * {@code POST …/register} (reprise 11/11/2026, Bref's {@code POST /v1/clients/register}): the code received once,
     * the client's name, a password (8–72 characters, Keevo's rule) and an optional e-mail.
     */
    public record Registration(@NotBlank @Size(max = 120) String name, @NotBlank @Size(max = 40) String phone,
                               @NotBlank @Size(max = 16) String code,
                               @NotBlank @Size(min = 8, max = 72,
                                       message = "Le mot de passe doit contenir entre 8 et 72 caractères")
                               String password,
                               @Size(max = 254) String email) {
    }

    /** {@code POST …/login} (reprise 11/11/2026, Bref's {@code POST /v1/clients/login}): phone or e-mail + password. */
    public record Login(@NotBlank @Size(max = 254) String identifier, @NotBlank @Size(max = 72) String password) {
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

    @Operation(summary = "Create the client account (Bref's registration) and open a portal session",
            description = "The portal code received by SMS once, a name, a password (8–72 characters) and an optional "
                    + "e-mail; later sign-ins use /login (no SMS). A phone that already has an account gets the new "
                    + "password (its other sessions end). 201. 401 OTP_INVALID, 429 OTP_TOO_MANY_ATTEMPTS / "
                    + "OTP_TOO_MANY_REQUESTS / PORTAL_TOO_MANY_REQUESTS, 409 EMAIL_ALREADY_EXISTS, 422 "
                    + "PHONE_COUNTRY_NOT_SUPPORTED / VALIDATION_FAILED / VALIDATION_ERROR.")
    @SecurityRequirements
    @PostMapping("/register")
    public ResponseEntity<ApiResponseWrapper<SessionDto>> register(@Valid @RequestBody Registration body,
                                                                   HttpServletRequest request) {
        PortalAuthService.Session session = auth.register(body.name(), body.phone(), body.code(), body.password(),
                body.email(), ClientIps.of(request));
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponseWrapper.ok(new SessionDto(session.accessToken(), session.expiresAt())));
    }

    @Operation(summary = "Sign in with phone or e-mail and password (no SMS)",
            description = "401 INVALID_CREDENTIALS (unknown phone / e-mail, account without password, wrong password: "
                    + "one answer), 429 PORTAL_TOO_MANY_REQUESTS (30 attempts per IP, 10 per identifier, 15 min), 422 "
                    + "VALIDATION_ERROR (field missing).")
    @SecurityRequirements
    @PostMapping("/login")
    public ResponseEntity<ApiResponseWrapper<SessionDto>> login(@Valid @RequestBody Login body,
                                                                HttpServletRequest request) {
        PortalAuthService.Session session = auth.login(body.identifier(), body.password(), ClientIps.of(request));
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
