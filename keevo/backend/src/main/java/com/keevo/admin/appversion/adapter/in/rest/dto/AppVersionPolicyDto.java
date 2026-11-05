package com.keevo.admin.appversion.adapter.in.rest.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.keevo.identity.api.AppVersionAdminPort.AppVersionPolicyView;

import java.time.Instant;

/** Story 19.10 — a minimum app version row as the super-admin dashboard shows it (nulls written). */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record AppVersionPolicyDto(String identity, String platform, String minVersion, String latestVersion,
                                  String storeUrl, String message, Instant updatedAt) {

    public static AppVersionPolicyDto from(AppVersionPolicyView v) {
        return new AppVersionPolicyDto(v.identity(), v.platform(), v.minVersion(), v.latestVersion(), v.storeUrl(),
                v.message(), v.updatedAt());
    }
}
