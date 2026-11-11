package com.keevo.identity.auth.domain.port.in;

import java.util.UUID;

/**
 * Story 19.6b — the account behind a login-step-1 token ({@code scope=login_pending}, 5 min), for the endpoints a person
 * reaches before choosing a company (answering an invitation). Same rule and errors as {@code select-tenant}.
 */
public interface LoginTokenVerifier {

    /**
     * @throws com.keevo.shared.domain.exception.DomainException TOKEN_EXPIRED or TOKEN_INVALID (wrong scope, malformed)
     */
    UUID userIdOf(String loginToken);
}
