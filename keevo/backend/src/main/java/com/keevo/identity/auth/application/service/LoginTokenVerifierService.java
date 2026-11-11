package com.keevo.identity.auth.application.service;

import com.keevo.identity.auth.domain.port.in.LoginTokenVerifier;
import com.keevo.shared.infrastructure.security.JwtTokenProvider;
import org.springframework.stereotype.Service;

import java.util.UUID;

/** Story 19.6b — {@link LoginTokenVerifier} over {@link LoginTokens} (one rule with select-tenant and create-company). */
@Service
public class LoginTokenVerifierService implements LoginTokenVerifier {

    private final JwtTokenProvider jwtTokenProvider;

    public LoginTokenVerifierService(JwtTokenProvider jwtTokenProvider) {
        this.jwtTokenProvider = jwtTokenProvider;
    }

    @Override
    public UUID userIdOf(String loginToken) {
        return LoginTokens.parseUserId(jwtTokenProvider, loginToken);
    }
}
