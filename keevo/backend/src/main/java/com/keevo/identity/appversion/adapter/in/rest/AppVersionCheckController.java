package com.keevo.identity.appversion.adapter.in.rest;

import com.keevo.identity.appversion.adapter.in.rest.dto.AppVersionCheckResponse;
import com.keevo.identity.appversion.application.service.AppVersionCheckRateLimiter;
import com.keevo.identity.appversion.domain.port.in.CheckAppVersionUseCase;
import com.keevo.shared.infrastructure.web.ClientIps;
import com.keevo.shared.module.EntitlementExempt;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;

import java.time.Duration;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Story 19.10 — public version check of the apps (no JWT: {@code SecurityConfig.PUBLIC_PATHS}). Raw JSON body, like
 * {@code WebPushController}: the Bref React Native app in stores reads it unwrapped. Errors keep the usual error
 * body. ZERO business logic: per-IP limit, then {@link CheckAppVersionUseCase}.
 */
@Tag(name = "App version", description = "Versions minimales des applications")
@EntitlementExempt("version d'application — public")
@RestController
@RequestMapping("/api/v1/app-version")
public class AppVersionCheckController {

    private final CheckAppVersionUseCase useCase;
    private final AppVersionCheckRateLimiter rateLimiter;

    public AppVersionCheckController(CheckAppVersionUseCase useCase, AppVersionCheckRateLimiter rateLimiter) {
        this.useCase = useCase;
        this.rateLimiter = rateLimiter;
    }

    @Operation(summary = "Is an update required or available for this app build?",
            description = "identity: app identity (kebab-case), absent or blank = bref (the Bref app in stores sends "
                    + "none). platform: ios | android | web (case-insensitive). version: MAJOR[.MINOR[.PATCH]], "
                    + "-pre / +build suffix ignored. Raw body {updateRequired, updateAvailable, storeUrl, message, "
                    + "latestVersion, isForced, hasUpdate, changelog}: updateRequired = isForced = version below the "
                    + "minimum; updateAvailable = hasUpdate = below the latest version or required; latestVersion = "
                    + "the recorded latest version, else the sent one; changelog = message. Nothing recorded = all "
                    + "false. 1200 calls per minute per IP. Cache-Control: public, max-age=60 on 200.")
    @ApiResponse(responseCode = "200", description = "Answer (raw JSON, not wrapped)")
    @ApiResponse(responseCode = "422", description = "VALIDATION_ERROR — unknown platform, version missing or invalid")
    @ApiResponse(responseCode = "429", description = "RATE_LIMIT_EXCEEDED")
    @SecurityRequirements
    @GetMapping("/check")
    public ResponseEntity<AppVersionCheckResponse> check(@RequestParam(required = false) String identity,
                                         @RequestParam(required = false) String platform,
                                         @RequestParam(required = false) String version,
                                         HttpServletRequest request) {
        rateLimiter.acquire(ClientIps.of(request));
        AppVersionCheckResponse body = AppVersionCheckResponse.from(useCase.check(identity, platform, version));
        // the answer changes only when the super-admin edits a row: shared caches may keep it a minute (200 only)
        return ResponseEntity.ok().cacheControl(CacheControl.maxAge(Duration.ofSeconds(60)).cachePublic()).body(body);
    }
}
