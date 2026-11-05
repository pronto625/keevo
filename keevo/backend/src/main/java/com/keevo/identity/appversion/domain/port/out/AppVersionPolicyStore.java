package com.keevo.identity.appversion.domain.port.out;

import com.keevo.identity.appversion.domain.model.AppPlatform;
import com.keevo.identity.appversion.domain.model.AppVersionPolicy;

import java.util.List;
import java.util.Optional;

/** Story 19.10 — persistence of {@code public.app_versions} (runs on the caller's transaction). */
public interface AppVersionPolicyStore {

    /** The policy of one identity and platform: one primary-key read. */
    Optional<AppVersionPolicy> find(String appIdentity, AppPlatform platform);

    /** Every policy, by identity then platform. */
    List<AppVersionPolicy> findAll();

    /** Inserts or replaces the policy; {@code true} when it was created. */
    boolean upsert(AppVersionPolicy policy);

    /** Deletes the policy in one statement; the deleted row, or empty when there was none. */
    Optional<AppVersionPolicy> delete(String appIdentity, AppPlatform platform);
}
