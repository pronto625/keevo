package com.keevo.identity.auth.domain.port.in;

/** Story 19.2 — sends a 6-digit sign-in code. Never reveals whether an account exists. */
public interface RequestOtpUseCase {

    /** @return validity of the code in seconds (same answer whether or not an account exists) */
    int request(RequestOtpCommand command);
}
