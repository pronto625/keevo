package com.keevo.identity.auth.adapter.in.rest;

import com.keevo.identity.api.Role;
import com.keevo.identity.auth.adapter.in.rest.dto.AppleSignInDto;
import com.keevo.identity.auth.adapter.in.rest.dto.CreateCompanyRequestDto;
import com.keevo.identity.auth.adapter.in.rest.dto.GoogleSignInDto;
import com.keevo.identity.auth.adapter.in.rest.dto.LoginRequest;
import com.keevo.identity.auth.adapter.in.rest.dto.OtpRequestDto;
import com.keevo.identity.auth.adapter.in.rest.dto.OtpRequestResponseDto;
import com.keevo.identity.auth.adapter.in.rest.dto.OtpVerifyDto;
import com.keevo.identity.auth.adapter.in.rest.dto.LoginResponse;
import com.keevo.identity.auth.adapter.in.rest.dto.LoginSessionResponse;
import com.keevo.identity.auth.adapter.in.rest.dto.RefreshRequest;
import com.keevo.identity.auth.adapter.in.rest.dto.RegistrationRequest;
import com.keevo.identity.auth.adapter.in.rest.dto.RegistrationResponse;
import com.keevo.identity.auth.adapter.in.rest.dto.SelectTenantRequest;
import com.keevo.identity.auth.adapter.in.rest.dto.ChangePasswordRequestDto;
import com.keevo.identity.auth.adapter.in.rest.dto.UserProfileResponse;
import com.keevo.identity.auth.adapter.in.rest.dto.ForgotPasswordRequestDto;
import com.keevo.identity.auth.adapter.in.rest.dto.ForgotPasswordResponseDto;
import com.keevo.identity.auth.adapter.in.rest.dto.ResetPasswordRequestDto;
import com.keevo.identity.auth.adapter.in.rest.dto.ResetPasswordResponseDto;
import com.keevo.identity.auth.domain.model.AuthTokens;
import com.keevo.identity.auth.domain.model.DeviceInfo;
import com.keevo.identity.auth.domain.model.LegacyClients;
import com.keevo.identity.auth.application.service.RefreshRateLimiter;
import jakarta.servlet.http.HttpServletRequest;
import com.keevo.identity.auth.domain.model.UserMembershipInfo;
import com.keevo.identity.auth.domain.model.UserProfileData;
import com.keevo.identity.auth.domain.model.Usernames;
import com.keevo.identity.auth.domain.model.OtpChannel;
import com.keevo.identity.auth.domain.model.SocialProvider;
import com.keevo.identity.auth.domain.port.in.SocialSignInCommand;
import com.keevo.identity.auth.domain.port.in.SocialSignInUseCase;
import com.keevo.identity.auth.domain.port.in.AuthenticateUserCommand;
import com.keevo.identity.auth.domain.port.in.CreateCompanyCommand;
import com.keevo.identity.auth.domain.port.in.CreateCompanyUseCase;
import com.keevo.identity.auth.domain.port.in.RequestOtpCommand;
import com.keevo.identity.auth.domain.port.in.RequestOtpUseCase;
import com.keevo.identity.auth.domain.port.in.VerifyOtpCommand;
import com.keevo.identity.auth.domain.port.in.VerifyOtpUseCase;
import com.keevo.identity.auth.domain.port.in.AuthenticateUserUseCase;
import com.keevo.identity.auth.domain.port.in.GetUserProfileUseCase;
import com.keevo.identity.auth.domain.port.in.LoginSessionResult;
import com.keevo.identity.auth.domain.port.in.RefreshTokenUseCase;
import com.keevo.identity.auth.domain.port.in.RegisterUserCommand;
import com.keevo.identity.auth.domain.port.in.RegisterUserUseCase;
import com.keevo.identity.auth.domain.port.in.RegistrationResult;
import com.keevo.identity.auth.domain.port.in.SelectTenantCommand;
import com.keevo.identity.auth.domain.port.in.SelectTenantUseCase;
import com.keevo.identity.auth.domain.port.in.RequestPasswordResetCommand;
import com.keevo.identity.auth.domain.port.in.RequestPasswordResetUseCase;
import com.keevo.identity.auth.domain.port.in.ResetPasswordCommand;
import com.keevo.identity.auth.domain.port.in.ResetPasswordUseCase;
import com.keevo.identity.employee.domain.port.in.ChangePasswordCommand;
import com.keevo.identity.employee.domain.port.in.ChangePasswordUseCase;
import com.keevo.shared.infrastructure.persistence.TenantContext;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import com.keevo.shared.infrastructure.web.ClientIps;
import com.keevo.shared.module.EntitlementExempt;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

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
 * - POST /api/v1/auth/otp/request, /otp/verify, /create-company (Story 19.2)
 * - POST /api/v1/auth/social/google, /social/apple (Story 19.3)
 *
 * <p>Story 19.10 — legacy clients: a request to login, otp/verify, social/*, select-tenant or refresh WITHOUT a
 * non-blank {@value #APP_VERSION_HEADER} header comes from an app that does not declare its version (Flutter V1).
 * For such a client MANAGER / VIEWER memberships are left out of the login lists and refused at select-tenant and
 * refresh (403 APP_UPDATE_REQUIRED, see {@link LegacyClients}). OWNER / EMPLOYEE are never affected. Switch:
 * {@code keevo.app-version.legacy-role-guard.enabled} (default true).
 */
@Tag(name = "Authentication", description = "User registration and login endpoints")
@EntitlementExempt("authentification")
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    /** Story 19.10 — the header new apps send with their version; its absence marks a legacy (V1) client. */
    public static final String APP_VERSION_HEADER = "X-App-Version";

    private final RegisterUserUseCase    registerUserUseCase;
    private final AuthenticateUserUseCase authenticateUserUseCase;
    private final SelectTenantUseCase    selectTenantUseCase;
    private final RefreshTokenUseCase    refreshTokenUseCase;
    private final ChangePasswordUseCase  changePasswordUseCase;
    private final GetUserProfileUseCase  getUserProfileUseCase;
    private final RequestPasswordResetUseCase requestPasswordResetUseCase;
    private final ResetPasswordUseCase   resetPasswordUseCase;
    private final RefreshRateLimiter     refreshRateLimiter;
    private final RequestOtpUseCase      requestOtpUseCase;
    private final VerifyOtpUseCase       verifyOtpUseCase;
    private final CreateCompanyUseCase   createCompanyUseCase;
    private final SocialSignInUseCase    socialSignInUseCase;
    private final boolean                legacyRoleGuard;

    /** Guard on (the default) — used by the tests that build the controller by hand. */
    public AuthController(RegisterUserUseCase registerUserUseCase,
                          AuthenticateUserUseCase authenticateUserUseCase,
                          SelectTenantUseCase selectTenantUseCase,
                          RefreshTokenUseCase refreshTokenUseCase,
                          ChangePasswordUseCase changePasswordUseCase,
                          GetUserProfileUseCase getUserProfileUseCase,
                          RequestPasswordResetUseCase requestPasswordResetUseCase,
                          ResetPasswordUseCase resetPasswordUseCase,
                          RefreshRateLimiter refreshRateLimiter,
                          RequestOtpUseCase requestOtpUseCase,
                          VerifyOtpUseCase verifyOtpUseCase,
                          CreateCompanyUseCase createCompanyUseCase,
                          SocialSignInUseCase socialSignInUseCase) {
        this(registerUserUseCase, authenticateUserUseCase, selectTenantUseCase, refreshTokenUseCase,
                changePasswordUseCase, getUserProfileUseCase, requestPasswordResetUseCase, resetPasswordUseCase,
                refreshRateLimiter, requestOtpUseCase, verifyOtpUseCase, createCompanyUseCase, socialSignInUseCase,
                true);
    }

    @Autowired
    public AuthController(RegisterUserUseCase registerUserUseCase,
                          AuthenticateUserUseCase authenticateUserUseCase,
                          SelectTenantUseCase selectTenantUseCase,
                          RefreshTokenUseCase refreshTokenUseCase,
                          ChangePasswordUseCase changePasswordUseCase,
                          GetUserProfileUseCase getUserProfileUseCase,
                          RequestPasswordResetUseCase requestPasswordResetUseCase,
                          ResetPasswordUseCase resetPasswordUseCase,
                          RefreshRateLimiter refreshRateLimiter,
                          RequestOtpUseCase requestOtpUseCase,
                          VerifyOtpUseCase verifyOtpUseCase,
                          CreateCompanyUseCase createCompanyUseCase,
                          SocialSignInUseCase socialSignInUseCase,
                          @Value("${keevo.app-version.legacy-role-guard.enabled:true}") boolean legacyRoleGuard) {
        this.registerUserUseCase    = registerUserUseCase;
        this.authenticateUserUseCase = authenticateUserUseCase;
        this.selectTenantUseCase    = selectTenantUseCase;
        this.refreshTokenUseCase    = refreshTokenUseCase;
        this.changePasswordUseCase  = changePasswordUseCase;
        this.getUserProfileUseCase  = getUserProfileUseCase;
        this.requestPasswordResetUseCase = requestPasswordResetUseCase;
        this.resetPasswordUseCase   = resetPasswordUseCase;
        this.refreshRateLimiter     = refreshRateLimiter;
        this.requestOtpUseCase      = requestOtpUseCase;
        this.verifyOtpUseCase       = verifyOtpUseCase;
        this.createCompanyUseCase   = createCompanyUseCase;
        this.socialSignInUseCase    = socialSignInUseCase;
        this.legacyRoleGuard        = legacyRoleGuard;
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
                result.tenantId(),
                result.refreshToken()
            ));
    }

    // ── Login step 1 ─────────────────────────────────────────────────────────

    @Operation(
        summary = "Authenticate user (step 1 — two-step login)",
        description = """
            Story 1.7 — Two-step login.

            Step 1: Authenticates with phone + password, or (Story 19.4) username + password: send exactly one of
            phoneNumber / username (both or neither → 422 VALIDATION_FAILED). The username is compared
            case-insensitively; on the username path any non-blank password is accepted (no length check before
            the lookup). The response, the lockout and the next step are the same for both. An IP with 50 failed
            logins in the hour is answered 429 RATE_LIMIT_EXCEEDED (successful logins are never counted).
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
        @ApiResponse(responseCode = "401", description = "INVALID_CREDENTIALS (« Téléphone ou mot de passe incorrect » on "
            + "the phone path, « Identifiant ou mot de passe incorrect » on the username path) or ACCOUNT_LOCKED"),
        @ApiResponse(responseCode = "422", description = "Validation error; VALIDATION_FAILED « Indiquez un numéro de "
            + "téléphone ou un identifiant » when both or neither of phoneNumber / username are sent"),
        @ApiResponse(responseCode = "429", description = "RATE_LIMIT_EXCEEDED — too many failed logins from this IP "
            + "(50 per hour)")
    })
    @SecurityRequirements
    @PostMapping("/login")
    public ResponseEntity<LoginSessionResponse> login(
            @Valid @RequestBody LoginRequest request, HttpServletRequest httpRequest) {

        // Story 19.4 — exactly one identifier: the phone path (V1, unchanged) or the username path (members).
        if (request.hasPhoneNumber() == request.hasUsername()) {
            throw DomainException.userFacing(ErrorCode.VALIDATION_FAILED, Usernames.IDENTIFIER_REQUIRED_MESSAGE);
        }
        String ip = clientIp(httpRequest);
        AuthenticateUserCommand command = request.hasUsername()
                ? AuthenticateUserCommand.byUsername(request.username(), request.password(), ip)
                : AuthenticateUserCommand.byPhone(request.phoneNumber(), request.password(), ip);

        LoginSessionResult result = authenticateUserUseCase.authenticate(command);

        return ResponseEntity.ok(toLoginSessionResponse(result, httpRequest));
    }

    // ── Login step 2: select-tenant ───────────────────────────────────────────

    @Operation(
        summary = "Select tenant (step 2 — two-step login)",
        description = """
            Story 1.7 — Two-step login step 2.

            Validates the loginToken from step 1, verifies the user's membership in
            the requested tenant, and issues a full RS256 access token (30 min) +
            opaque refresh token (365 days sliding, one device session) scoped to that tenant.

            No JWT authentication required — publicly accessible.
            """
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Tenant selected — access token issued",
            content = @Content(schema = @Schema(implementation = LoginResponse.class))),
        @ApiResponse(responseCode = "401",
            description = "loginToken expired, invalid, or wrong scope"),
        @ApiResponse(responseCode = "403", description = "APP_UPDATE_REQUIRED — MANAGER / VIEWER membership opened "
            + "without the X-App-Version header (legacy app)"),
        @ApiResponse(responseCode = "404", description = "Tenant not found or no membership"),
        @ApiResponse(responseCode = "422", description = "Validation error")
    })
    @SecurityRequirements
    @PostMapping("/select-tenant")
    public ResponseEntity<LoginResponse> selectTenant(
            @Valid @RequestBody SelectTenantRequest request, HttpServletRequest httpRequest) {

        AuthTokens tokens = selectTenantUseCase.select(new SelectTenantCommand(
                request.loginToken(), request.tenantCode(),
                DeviceInfo.of(request.deviceId(), request.deviceLabel()), legacyClient(httpRequest)));

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
        @ApiResponse(responseCode = "403", description = "APP_UPDATE_REQUIRED — MANAGER / VIEWER session renewed "
            + "without the X-App-Version header (legacy app)"),
        @ApiResponse(responseCode = "422", description = "Validation error")
    })
    @SecurityRequirements
    @PostMapping("/refresh")
    public ResponseEntity<LoginResponse> refresh(
            @Valid @RequestBody RefreshRequest request, HttpServletRequest httpRequest) {

        // Story 16.11 — per-token and per-IP rate limit (429 « Trop de requêtes »).
        refreshRateLimiter.acquire(request.refreshToken(), clientIp(httpRequest));
        AuthTokens tokens = refreshTokenUseCase.refresh(request.refreshToken(),
                DeviceInfo.of(request.deviceId(), request.deviceLabel()), legacyClient(httpRequest));
        return ResponseEntity.ok(toLoginResponse(tokens));
    }

    /**
     * POST /api/v1/auth/logout — revokes the refresh token so a signed-out
     * device's token can never renew a session. Public: the access token may
     * already be expired when the user signs out. Always 204 (no oracle).
     */
    @PostMapping("/logout")
    public ResponseEntity<Void> logout(@RequestBody(required = false) RefreshRequest request) {
        if (request != null && request.refreshToken() != null && !request.refreshToken().isBlank()) {
            refreshTokenUseCase.revoke(request.refreshToken());
        }
        return ResponseEntity.noContent().build();
    }

    // ── Profile (Story 8.6) ───────────────────────────────────────────────────

    @Operation(
        summary = "Get authenticated user profile",
        description = """
            Story 8.6 — AC3, AC4: Returns the profile of the currently authenticated user.

            EMPLOYEE: firstName, lastName, storeId, storeName resolved from employee record.
            OWNER: firstName, lastName, storeId, storeName are null.

            Requires valid JWT access token.
            """
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Profile retrieved",
            content = @Content(schema = @Schema(implementation = UserProfileResponse.class))),
        @ApiResponse(responseCode = "401", description = "Not authenticated")
    })
    @GetMapping("/profile")
    public ResponseEntity<UserProfileResponse> getProfile() {
        UUID userId = (UUID) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        String tenantId = TenantContext.getCurrentTenant();
        UserProfileData data = getUserProfileUseCase.execute(userId, tenantId);
        return ResponseEntity.ok(toProfileResponse(data));
    }

    // ── Change Password (Story 3.5) ──────────────────────────────────────────

    @Operation(
        summary = "Change password (employee forced change)",
        description = """
            Story 3.5 — AC4: Employee forced password change on first login.
            Requires valid JWT (any role). Updates bcrypt hash, clears passwordChangeRequired flag,
            revokes old tokens, and returns new JWT + refresh token.
            """
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Password changed — new tokens issued"),
        @ApiResponse(responseCode = "401", description = "Current password wrong"),
        @ApiResponse(responseCode = "422", description = "New password does not meet requirements")
    })
    @PostMapping("/change-password")
    public ResponseEntity<LoginResponse> changePassword(
            @Valid @RequestBody ChangePasswordRequestDto request) {
        UUID userId = (UUID) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        AuthTokens tokens = changePasswordUseCase.execute(
                new ChangePasswordCommand(userId, request.currentPassword(), request.newPassword()));
        return ResponseEntity.ok(toLoginResponse(tokens));
    }

    // ── Forgot Password (Story 14.12) ────────────────────────────────────────

    @Operation(
        summary = "Request password reset via OTP",
        description = """
            Story 14.12 — Sends a 6-digit OTP via WhatsApp for password reset.

            Public endpoint — no authentication required.
            Anti-enumeration: always returns 200 {"sent":true} regardless of
            whether the phone number exists or is rate-limited.
            Rate-limited: 1 request/min + 5/hour per phone number.
            """
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "OTP sent if phone exists (silent otherwise)",
            content = @Content(schema = @Schema(implementation = ForgotPasswordResponseDto.class))),
        @ApiResponse(responseCode = "422", description = "Validation error")
    })
    @SecurityRequirements
    @PostMapping("/forgot-password")
    public ResponseEntity<ForgotPasswordResponseDto> forgotPassword(
            @Valid @RequestBody ForgotPasswordRequestDto request) {
        requestPasswordResetUseCase.execute(
                new RequestPasswordResetCommand(request.phoneNumber()));
        return ResponseEntity.ok(new ForgotPasswordResponseDto(true));
    }

    // ── Reset Password (Story 14.12) ──────────────────────────────────────────

    @Operation(
        summary = "Reset password using OTP",
        description = """
            Story 14.12 — Verifies a 6-digit OTP code and resets the password.

            Public endpoint — no authentication required.
            Revokes ALL existing sessions across all tenants.
            Does NOT issue new tokens — user must re-login via normal two-step flow.
            Token is locked after 5 failed attempts.
            """
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Password reset successful",
            content = @Content(schema = @Schema(implementation = ResetPasswordResponseDto.class))),
        @ApiResponse(responseCode = "422", description = "Invalid/expired code, locked token, or weak password")
    })
    @SecurityRequirements
    @PostMapping("/reset-password")
    public ResponseEntity<ResetPasswordResponseDto> resetPassword(
            @Valid @RequestBody ResetPasswordRequestDto request) {
        resetPasswordUseCase.execute(new ResetPasswordCommand(
                request.phoneNumber(), request.code(), request.newPassword()));
        return ResponseEntity.ok(new ResetPasswordResponseDto(true));
    }

    // ── Sign-in code (Story 19.2) ─────────────────────────────────────────────

    @Operation(
        summary = "Request a sign-in code (SMS, WhatsApp or email)",
        description = """
            Story 19.2 — sends a 6-digit code valid 5 minutes. Public endpoint.

            identifier: email, or phone in E.164 (00 accepted for +) of an allowed country
            (Cameroun, Gabon, Tchad, RD Congo, Sénégal by default).
            channel (optional): SMS | WHATSAPP | EMAIL. Email → EMAIL only; phone → the given channel,
            or SMS and WhatsApp when absent.

            Always 200 {"expiresIn":300}, whether or not an account exists. Messages in French, English when
            Accept-Language starts with "en".
            Limits: 3 requests / 10 min and 5 / hour per identifier, 10 / hour per IP, and 20 wrong codes
            in 24 h block the identifier → 429 OTP_TOO_MANY_REQUESTS.
            """
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Code sent (silent when the account does not exist)",
            content = @Content(schema = @Schema(implementation = OtpRequestResponseDto.class))),
        @ApiResponse(responseCode = "422",
            description = "VALIDATION_FAILED (identifier or channel) or PHONE_COUNTRY_NOT_SUPPORTED"),
        @ApiResponse(responseCode = "429", description = "OTP_TOO_MANY_REQUESTS")
    })
    @SecurityRequirements
    @PostMapping("/otp/request")
    public ResponseEntity<OtpRequestResponseDto> requestOtp(
            @Valid @RequestBody OtpRequestDto request,
            @RequestHeader(value = "Accept-Language", required = false) String acceptLanguage,
            HttpServletRequest httpRequest) {
        int expiresIn = requestOtpUseCase.request(new RequestOtpCommand(
                request.identifier(), OtpChannel.parseOptional(request.channel()),
                clientIp(httpRequest), acceptLanguage));
        return ResponseEntity.ok(new OtpRequestResponseDto(expiresIn));
    }

    @Operation(
        summary = "Verify a sign-in code (login step 1)",
        description = """
            Story 19.2 — checks the code and answers exactly like /auth/login: a loginToken (5 min) and the
            memberships. An unknown identifier gets a new account without company: memberships is empty and
            the app calls /auth/create-company with the loginToken. Otherwise /auth/select-tenant as usual.

            Wrong code → 401 OTP_INVALID; the 5th wrong code kills it → 429 OTP_TOO_MANY_ATTEMPTS.
            Expired, used or unknown code → 401 OTP_INVALID. 20 wrong codes in 24 h, or more than 30 checks
            per hour from one IP → 429 OTP_TOO_MANY_REQUESTS.
            """
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Code accepted — loginToken + memberships",
            content = @Content(schema = @Schema(implementation = LoginSessionResponse.class))),
        @ApiResponse(responseCode = "401", description = "OTP_INVALID"),
        @ApiResponse(responseCode = "409", description = "USER_ALREADY_EXISTS (concurrent account creation)"),
        @ApiResponse(responseCode = "422", description = "VALIDATION_FAILED or PHONE_COUNTRY_NOT_SUPPORTED"),
        @ApiResponse(responseCode = "429", description = "OTP_TOO_MANY_ATTEMPTS or OTP_TOO_MANY_REQUESTS")
    })
    @SecurityRequirements
    @PostMapping("/otp/verify")
    public ResponseEntity<LoginSessionResponse> verifyOtp(@Valid @RequestBody OtpVerifyDto request,
                                                          HttpServletRequest httpRequest) {
        LoginSessionResult result = verifyOtpUseCase.verify(
                new VerifyOtpCommand(request.identifier(), request.code(), clientIp(httpRequest)));
        return ResponseEntity.ok(toLoginSessionResponse(result, httpRequest));
    }

    @Operation(
        summary = "Create my company (account without company)",
        description = """
            Story 19.2 — with the loginToken of /auth/otp/verify, creates the company exactly like
            /auth/register (trial plan, owner) and opens its session: no /auth/select-tenant needed.
            Same token errors as /auth/select-tenant. Refused when the user already owns a company.
            """
    )
    @ApiResponses({
        @ApiResponse(responseCode = "201", description = "Company created — session issued",
            content = @Content(schema = @Schema(implementation = RegistrationResponse.class))),
        @ApiResponse(responseCode = "401", description = "TOKEN_EXPIRED or TOKEN_INVALID"),
        @ApiResponse(responseCode = "409", description = "COMPANY_ALREADY_EXISTS"),
        @ApiResponse(responseCode = "422", description = "Validation error")
    })
    @SecurityRequirements
    @PostMapping("/create-company")
    public ResponseEntity<RegistrationResponse> createCompany(@Valid @RequestBody CreateCompanyRequestDto request) {
        RegistrationResult result = createCompanyUseCase.create(new CreateCompanyCommand(
                request.loginToken(), DeviceInfo.of(request.deviceId(), request.deviceLabel())));
        return ResponseEntity.status(HttpStatus.CREATED).body(new RegistrationResponse(
                result.tenantCode(), result.token(), result.userId(), result.tenantId(), result.refreshToken()));
    }

    // ── Google / Apple sign-in (Story 19.3) ───────────────────────────────────

    @Operation(
        summary = "Sign in with Google (login step 1)",
        description = """
            Story 19.3 — verifies the Google ID token on the server (Google's published keys, issuer, audience
            among the configured client ids, expiry) and answers exactly like /auth/login: a loginToken (5 min)
            and the memberships. The account is found by its Google identity, else linked by the verified email
            of an owner whose email is verified, else created without company (memberships empty → the app
            calls /auth/create-company with the loginToken).

            firstName / lastName (optional) override Google's names for a new account.
            nonce (optional, Story 19.3b): the clear nonce whose SHA-256 hex was given to Google; the token must
            carry that hash, and a nonce is accepted once. A token carrying a nonce claim requires it.
            Refused token or unverified Google email → 401 SOCIAL_TOKEN_INVALID. Email of a team member or
            never verified → 409 EMAIL_ALREADY_EXISTS. More than 30 calls per hour from one IP → 429.
            """
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Signed in — loginToken + memberships",
            content = @Content(schema = @Schema(implementation = LoginSessionResponse.class))),
        @ApiResponse(responseCode = "401", description = "SOCIAL_TOKEN_INVALID"),
        @ApiResponse(responseCode = "409", description = "EMAIL_ALREADY_EXISTS (email of a member or never verified)"),
        @ApiResponse(responseCode = "422", description = "Validation error"),
        @ApiResponse(responseCode = "429", description = "RATE_LIMIT_EXCEEDED")
    })
    @SecurityRequirements
    @PostMapping("/social/google")
    public ResponseEntity<LoginSessionResponse> signInWithGoogle(@Valid @RequestBody GoogleSignInDto request,
                                                                 HttpServletRequest httpRequest) {
        LoginSessionResult result = socialSignInUseCase.signIn(new SocialSignInCommand(SocialProvider.GOOGLE,
                request.idToken(), request.firstName(), request.lastName(), clientIp(httpRequest), request.nonce(), null));
        return ResponseEntity.ok(toLoginSessionResponse(result, httpRequest));
    }

    @Operation(
        summary = "Sign in with Apple (login step 1)",
        description = """
            Story 19.3 — verifies the Apple identity token on the server (Apple's published keys, issuer,
            audience among the configured bundle ids, expiry) and answers exactly like /auth/login. Apple may
            omit the email after the first consent: the account is then found by its Apple identity; a new one
            without email is created without company. firstName / lastName: send the names Apple gives at the
            first consent. nonce (optional, Story 19.3b): as for Google. authorizationCode (optional, Story 19.3b):
            kept, as an Apple refresh token, to revoke the Apple link when the account is deleted.

            Refused token → 401 SOCIAL_TOKEN_INVALID. Email of a team member or never verified → 409
            EMAIL_ALREADY_EXISTS. More than 30 calls per hour from one IP → 429.
            """
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Signed in — loginToken + memberships",
            content = @Content(schema = @Schema(implementation = LoginSessionResponse.class))),
        @ApiResponse(responseCode = "401", description = "SOCIAL_TOKEN_INVALID"),
        @ApiResponse(responseCode = "409", description = "EMAIL_ALREADY_EXISTS (email of a member or never verified)"),
        @ApiResponse(responseCode = "422", description = "Validation error"),
        @ApiResponse(responseCode = "429", description = "RATE_LIMIT_EXCEEDED")
    })
    @SecurityRequirements
    @PostMapping("/social/apple")
    public ResponseEntity<LoginSessionResponse> signInWithApple(@Valid @RequestBody AppleSignInDto request,
                                                                HttpServletRequest httpRequest) {
        LoginSessionResult result = socialSignInUseCase.signIn(new SocialSignInCommand(SocialProvider.APPLE,
                request.identityToken(), request.firstName(), request.lastName(), clientIp(httpRequest), request.nonce(),
                request.authorizationCode()));
        return ResponseEntity.ok(toLoginSessionResponse(result, httpRequest));
    }

    // ── Mapping helpers ───────────────────────────────────────────────────────

    /**
     * Client IP for the refresh rate limit: the right-most {@code X-Forwarded-For} entry (the one the
     * reverse proxy appended — a client cannot forge it), else the socket address.
     */
    static String clientIp(HttpServletRequest request) {
        return ClientIps.of(request);
    }

    /**
     * Story 19.10 — whether the request comes from a legacy app to guard: the guard is on and no non-blank
     * {@value #APP_VERSION_HEADER} header is sent (V1 2.0.1 sends none).
     */
    boolean legacyClient(HttpServletRequest request) {
        if (!legacyRoleGuard) {
            return false;
        }
        String version = request.getHeader(APP_VERSION_HEADER);
        return version == null || version.isBlank();
    }

    private UserProfileResponse toProfileResponse(UserProfileData data) {
        return new UserProfileResponse(
            data.userId(),
            data.phoneNumber(),
            Role.v1Label(data.role()),
            data.firstName(),
            data.lastName(),
            data.storeId(),
            data.storeName(),
            data.passwordChangeRequired(),
            data.tenantStatus(),
            data.onboardingCompleted(),
            data.username(),
            data.role()
        );
    }

    /** Story 19.10 — a legacy client gets no MANAGER / VIEWER membership in the list (in-memory filter, no query). */
    private LoginSessionResponse toLoginSessionResponse(LoginSessionResult result, HttpServletRequest httpRequest) {
        boolean legacy = legacyClient(httpRequest);
        List<LoginSessionResponse.MembershipDto> dtos = result.memberships().stream()
                .filter(m -> !legacy || !LegacyClients.refuses(m.role()))
                .map(m -> new LoginSessionResponse.MembershipDto(
                        m.tenantCode(), m.tenantName(), Role.v1Label(m.role()), m.schemaName(), m.role()))
                .toList();
        return new LoginSessionResponse(result.loginToken(), dtos);
    }

    private LoginResponse toLoginResponse(AuthTokens tokens) {
        return new LoginResponse(
            tokens.accessToken(),
            tokens.refreshToken(),
            tokens.userId(),
            tokens.tenantId(),
            Role.v1Label(tokens.role()),
            tokens.expiresIn(),
            tokens.storeId(),
            tokens.passwordChangeRequired(),
            tokens.role()
        );
    }
}
