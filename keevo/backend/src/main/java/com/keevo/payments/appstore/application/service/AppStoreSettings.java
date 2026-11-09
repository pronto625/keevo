package com.keevo.payments.appstore.application.service;

/**
 * Story 21.2 — settings of the App Store purchases ({@code keevo.payments.app-store.*}).
 *
 * @param webhookSecret      the exact {@code Authorization} header value RevenueCat sends
 *                           ({@code REVENUECAT_WEBHOOK_SECRET}); blank = every webhook call refused
 * @param acceptSandbox      sandbox purchases activate plans (dev/test profiles only)
 * @param reviewAccountEmail Apple review account: its sandbox purchases are accepted anywhere ({@code null} = none)
 */
public record AppStoreSettings(String webhookSecret, boolean acceptSandbox, String reviewAccountEmail) {

    @Override
    public String toString() { // never print the secret
        return "AppStoreSettings[acceptSandbox=" + acceptSandbox + ", reviewAccount="
                + (reviewAccountEmail != null) + "]";
    }
}
