package com.keevo.identity.auth.adapter.in.rest;

import com.keevo.identity.auth.adapter.in.rest.dto.LoginRequest;
import com.keevo.identity.auth.adapter.in.rest.dto.LoginResponse;
import com.keevo.identity.auth.adapter.in.rest.dto.LoginSessionResponse;
import com.keevo.identity.auth.adapter.in.rest.dto.RefreshRequest;
import com.keevo.identity.auth.adapter.in.rest.dto.RegistrationRequest;
import com.keevo.identity.auth.adapter.in.rest.dto.RegistrationResponse;
import com.keevo.identity.auth.adapter.in.rest.dto.SelectTenantRequest;
import com.keevo.identity.auth.domain.model.AuthTokens;
import com.keevo.identity.auth.domain.model.UserMembershipInfo;
import com.keevo.identity.auth.domain.port.in.AuthenticateUserCommand;
import com.keevo.identity.auth.domain.port.in.AuthenticateUserUseCase;
import com.keevo.identity.auth.domain.port.in.LoginSessionResult;
import com.keevo.identity.auth.domain.port.in.RefreshTokenUseCase;
import com.keevo.identity.auth.domain.port.in.RegisterUserCommand;
import com.keevo.identity.auth.domain.port.in.RegisterUserUseCase;
import com.keevo.identity.auth.domain.port.in.RegistrationResult;
import com.keevo.identity.auth.domain.port.in.SelectTenantCommand;
import com.keevo.identity.auth.domain.port.in.SelectTenantUseCase;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * AuthController — REST adapter for authentication endpoints.
 *
 * <p>Architecture rules:
 * - ZERO business logic in this class
 * - Maps DTO → Command → delegates to use case → maps result → DTO
 * - Exception handling delegated to GlobalExceptionHandler (global @RestControllerAdvice)
 *
 * <p>Public endpoints (no authentication required — see SecurityConfig):
 * - POST /api/v1/auth/register
 * - POST /api/v1/auth/login         (step 1: returns loginToken + memberships)
 * - POST /api/v1/auth/select-tenant (step 2: returns full access + refresh tokens)
 * - POST /api/v1/auth/refresh
 */
@Tag(name = "Authentication", description = "User registration and login endpoints")
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final RegisterUserUseCase    registerUserUseCase;
    private final AuthenticateUserUseCase authenticateUserUseCase;
    private final SelectTenantUseCase    selectTenantUseCase;
    private final RefreshTokenUseCase    refreshTokenUseCase;

    public AuthController(RegisterUserUseCase registerUserUseCase,
                          AuthenticateUserUseCase authenticateUserUseCase,
                          SelectTenantUseCase selectTenantUseCase,
                          RefreshTokenUseCase refreshTokenUseCase) {
        this.registerUserUseCase    = registerUserUseCase;
        this.authenticateUserUseCase = authenticateUserUseCase;
        this.selectTenantUseCase    = selectTenantUseCase;
        this.refreshTokenUseCase    = refreshTokenUseCase;
    }

    @Operation(
        summary = "Register new user",
        description = """
            Creates a new user account and provisions an isolated tenant workspace.

            - Generates a unique tenant code KV-XXXXXX
            - Creates a dedicated PostgreSQL schema kv_xxxxxx
            - Returns a signed RS256 JWT access token and opaque refresh token

            No authentication required — this is a public endpoint.
            """
    )
    @ApiResponses({
        @ApiResponse(responseCode = "201", description = "Registration successful",
            content = @Content(schema = @Schema(implementation = RegistrationResponse.class))),
        @ApiResponse(responseCode = "409", description = "Phone number already registered"),
        @ApiResponse(responseCode = "422", description = "Validation error")
    })
    @SecurityRequirements
    @PostMapping("/register")
    public ResponseEntity<RegistrationResponse> register(
            @Valid @RequestBody RegistrationRequest request) {

        RegisterUserCommand command = new RegisterUserCommand(
            request.phoneNumber(),
            request.password(),
            null
        );

        RegistrationResult result = registerUserUseCase.register(command);

        return ResponseEntity
            .status(HttpStatus.CREATED)
            .body(new RegistrationResponse(
                result.tenantCode(),
                result.token(),
                result.userId(),
                result.tenantId()
            ));
    }

    // ── Login step 1 ─────────────────────────────────────────────────────────

    @Operation(
        summary = "Authenticate user (step 1 — two-step login)",
        description = """
            Story 1.7 — Two-step login.

            Step 1: Authenticates with phone + password.
            Returns a short-lived loginToken (5min) and the list of tenant memberships.

            Flutter auto-calls /auth/select-tenant when memberships.length == 1
            (transparent for single-tenant OWNER users — zero UX change).
            When memberships.length > 1, Flutter shows a tenant picker screen.

            Account is locked for 15 minutes after 5 consecutive failed attempts.
            """
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Authentication step 1 successful",
            content = @Content(schema = @Schema(implementation = LoginSessionResponse.class))),
        @ApiResponse(responseCode = "401", description = "Invalid credentials or account locked"),
        @ApiResponse(responseCode = "422", description = "Validation error")
    })
    @SecurityRequirements
    @PostMapping("/login")
    public ResponseEntity<LoginSessionResponse> login(
            @Valid @RequestBody LoginRequest request) {

        AuthenticateUserCommand command = new AuthenticateUserCommand(
            request.phoneNumber(),
            request.password(),
            null
        );

        LoginSessionResult result = authenticateUserUseCase.authenticate(command);

        return ResponseEntity.ok(toLoginSessionResponse(result));
    }

    // ── Login step 2: select-tenant ───────────────────────────────────────────

    @Operation(
        summary = "Select tenant (step 2 — two-step login)",
        description = """
            Story 1.7 — Two-step login step 2.

            Validates the loginToken from step 1, verifies the user's membership in
            the requested tenant, and issues a full RS256 access token (24h) +
            opaque refresh token (30 days) scoped to that tenant.

            No JWT authentication required — publicly accessible.
            """
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Tenant selected — access token issued",
            content = @Content(schema = @Schema(implementation = LoginResponse.class))),
        @ApiResponse(responseCode = "401",
            description = "loginToken expired, invalid, or wrong scope"),
        @ApiResponse(responseCode = "404", description = "Tenant not found or no membership"),
        @ApiResponse(responseCode = "422", description = "Validation error")
    })
    @SecurityRequirements
    @PostMapping("/select-tenant")
    public ResponseEntity<LoginResponse> selectTenant(
            @Valid @RequestBody SelectTenantRequest request) {

        AuthTokens tokens = selectTenantUseCase.select(
                new SelectTenantCommand(request.loginToken(), request.tenantCode()));

        return ResponseEntity.ok(toLoginResponse(tokens));
    }

    // ── Refresh ───────────────────────────────────────────────────────────────

    @Operation(
        summary = "Refresh access token",
        description = """
            Exchanges a valid refresh token for a new access token + rotated refresh token.
            The old refresh token is revoked upon successful rotation.
            """
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Token refreshed successfully",
            content = @Content(schema = @Schema(implementation = LoginResponse.class))),
        @ApiResponse(responseCode = "401", description = "Refresh token invalid, expired or revoked"),
        @ApiResponse(responseCode = "422", description = "Validation error")
    })
    @SecurityRequirements
    @PostMapping("/refresh")
    public ResponseEntity<LoginResponse> refresh(
            @Valid @RequestBody RefreshRequest request) {

        AuthTokens tokens = refreshTokenUseCase.refresh(request.refreshToken());
        return ResponseEntity.ok(toLoginResponse(tokens));
    }

    // ── Mapping helpers ───────────────────────────────────────────────────────

    private LoginSessionResponse toLoginSessionResponse(LoginSessionResult result) {
        List<LoginSessionResponse.MembershipDto> dtos = result.memberships().stream()
                .map(m -> new LoginSessionResponse.MembershipDto(
                        m.tenantCode(), m.tenantName(), m.role(), m.schemaName()))
                .toList();
        return new LoginSessionResponse(result.loginToken(), dtos);
    }

    private LoginResponse toLoginResponse(AuthTokens tokens) {
        return new LoginResponse(
            tokens.accessToken(),
            tokens.refreshToken(),
            tokens.userId(),
            tokens.tenantId(),
            tokens.role(),
            tokens.expiresIn()
        );
    }
}
