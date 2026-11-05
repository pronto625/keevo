package com.keevo.identity.appversion.adapter.in.rest.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.keevo.identity.appversion.domain.model.VersionCheck;

/**
 * Story 19.10 — raw body of {@code GET /api/v1/app-version/check} (no {@code ApiResponseWrapper}): the Keevo shape
 * ({@code updateRequired}, {@code updateAvailable}, {@code storeUrl}, {@code message}, {@code latestVersion}) and the
 * shape the Bref React Native app in stores already reads ({@code isForced} = updateRequired, {@code hasUpdate} =
 * updateAvailable, {@code changelog} = message). Null values are written ({@code storeUrl}, {@code message},
 * {@code changelog}).
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record AppVersionCheckResponse(
        @JsonProperty("updateRequired") boolean updateRequired,
        @JsonProperty("updateAvailable") boolean updateAvailable,
        @JsonProperty("storeUrl") String storeUrl,
        @JsonProperty("message") String message,
        @JsonProperty("latestVersion") String latestVersion,
        @JsonProperty("isForced") boolean isForced,
        @JsonProperty("hasUpdate") boolean hasUpdate,
        @JsonProperty("changelog") String changelog) {

    public static AppVersionCheckResponse from(VersionCheck check) {
        return new AppVersionCheckResponse(check.updateRequired(), check.updateAvailable(), check.storeUrl(),
                check.message(), check.latestVersion(), check.updateRequired(), check.updateAvailable(),
                check.message());
    }
}
