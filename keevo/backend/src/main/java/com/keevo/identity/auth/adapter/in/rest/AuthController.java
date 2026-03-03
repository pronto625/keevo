package com.keevo.identity.auth.adapter.in.rest;

import com.keevo.identity.auth.domain.port.in.RegisterUserCommand;
import com.keevo.identity.auth.domain.port.in.RegisterUserUseCase;
import com.keevo.identity.auth.domain.port.in.RegistrationResult;
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

/**
 * AuthController — REST adapter for authentication endpoints.
 *
 * <p>Architecture rules:
 * - ZERO business logic in this class
 * - Maps DTO → Command → delegates to use case → maps result → DTO
 * - Exception handling delegated to GlobalExceptionHandler
 *
 * <p>Public endpoints (no authentication required — see SecurityConfig):
 * - POST /api/v1/auth/register
 */
@Tag(name = "Authentication", description = "User registration and login endpoints")
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final RegisterUserUseCase registerUserUseCase;

    public AuthController(RegisterUserUseCase registerUserUseCase) {
        this.registerUserUseCase = registerUserUseCase;
    }

    @Operation(
        summary = "Register new user",
        description = """
            Creates a new user account and provisions an isolated tenant workspace.

            - Generates a unique tenant code KV-XXXXXX
            - Creates a dedicated PostgreSQL schema kv_xxxxxx
            - Returns a JWT token (stub in Story 1.2, real JWT in Story 1.3)

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
}
