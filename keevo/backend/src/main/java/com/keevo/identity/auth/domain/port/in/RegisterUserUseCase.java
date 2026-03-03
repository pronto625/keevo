package com.keevo.identity.auth.domain.port.in;

/**
 * RegisterUserUseCase — Driving port for user registration.
 *
 * <p>Interface boundary between REST/MCP adapter and application service.
 * Accepts only pure Java records — never HTTP/Spring/framework types (MCP readiness).
 */
public interface RegisterUserUseCase {

    /**
     * Register a new user and provision their isolated tenant workspace.
     *
     * @param command registration data (phone number, password)
     * @return registration result with tenant code and JWT token
     * @throws com.keevo.shared.domain.exception.DomainException
     *         with code USER_ALREADY_EXISTS if phone is taken
     */
    RegistrationResult register(RegisterUserCommand command);
}
