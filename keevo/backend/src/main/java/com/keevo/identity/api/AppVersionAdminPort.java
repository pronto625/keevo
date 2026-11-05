package com.keevo.identity.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Story 19.10 — super-admin edit of the minimum app versions ({@code public.app_versions}), per app identity
 * (kebab-case: {@code keevo}, {@code bref}…) and platform ({@code ios}, {@code android}, {@code web}). Every change is
 * audited ({@code [ADMIN AUDIT]} log + {@code updated_by} / {@code updated_at}).
 */
public interface AppVersionAdminPort {

    /** Every recorded policy, by identity then platform. */
    List<AppVersionPolicyView> list();

    /**
     * Creates or replaces the policy of {@code identity} / {@code platform}. Versions, store link and message may be
     * null (blank = null).
     *
     * @throws com.keevo.shared.domain.exception.DomainException APP_VERSION_INVALID (422) with a French reason,
     *                                                           nothing saved
     */
    SaveResult save(String identity, String platform, String minVersion, String latestVersion, String storeUrl,
                    String message, UUID actorId);

    /**
     * Deletes the policy of {@code identity} / {@code platform} (the app is then neither forced nor offered an update).
     *
     * @throws com.keevo.shared.domain.exception.DomainException APP_VERSION_NOT_FOUND (404)
     */
    void delete(String identity, String platform, UUID actorId);

    /** A recorded policy as the super-admin sees it. */
    record AppVersionPolicyView(String identity, String platform, String minVersion, String latestVersion,
                                String storeUrl, String message, Instant updatedAt) {
    }

    /** The saved policy and whether it was created (else replaced). */
    record SaveResult(AppVersionPolicyView policy, boolean created) {
    }
}
