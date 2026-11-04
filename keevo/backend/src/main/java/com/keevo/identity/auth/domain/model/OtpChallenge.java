package com.keevo.identity.auth.domain.model;

import java.util.UUID;

/** Story 19.2 — a live sign-in code challenge as read for verification (hash only, never the code). */
public record OtpChallenge(UUID id, String codeHash, int attempts) {}
