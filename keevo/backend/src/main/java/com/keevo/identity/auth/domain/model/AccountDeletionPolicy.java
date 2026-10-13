package com.keevo.identity.auth.domain.model;

import java.time.Duration;

/**
 * AccountDeletionPolicy — grace period between an account deletion request and the definitive
 * deletion (Story 8-5). Shared by the deletion flow and the admin alerts feed (Story 9-5).
 */
public final class AccountDeletionPolicy {

    public static final Duration GRACE_PERIOD = Duration.ofDays(30);

    private AccountDeletionPolicy() {}
}
