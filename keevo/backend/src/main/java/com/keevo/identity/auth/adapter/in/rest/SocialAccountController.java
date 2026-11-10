package com.keevo.identity.auth.adapter.in.rest;

import com.keevo.identity.auth.adapter.in.rest.dto.AppleLinkDto;
import com.keevo.identity.auth.adapter.in.rest.dto.GoogleLinkDto;
import com.keevo.identity.auth.adapter.in.rest.dto.SocialAccountDto;
import com.keevo.identity.auth.domain.model.SocialProvider;
import com.keevo.identity.auth.domain.port.in.SocialAccountUseCase;
import com.keevo.identity.auth.domain.port.in.SocialAccountUseCase.LinkCommand;
import com.keevo.identity.auth.domain.port.in.SocialAccountUseCase.LinkedSocialAccount;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import com.keevo.shared.infrastructure.security.CurrentActor;
import com.keevo.shared.infrastructure.web.ApiResponseWrapper;
import com.keevo.shared.infrastructure.web.ClientIps;
import com.keevo.shared.module.EntitlementExempt;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Locale;

/**
 * Story 19.3b — « Comptes Google et Apple » of the signed-in account (any role, VIEWER included: self-service).
 *
 * <ul>
 *   <li>{@code GET /api/v1/auth/social/accounts} — the links</li>
 *   <li>{@code POST /api/v1/auth/social/accounts/google} — link Google</li>
 *   <li>{@code POST /api/v1/auth/social/accounts/apple} — link Apple</li>
 *   <li>{@code DELETE /api/v1/auth/social/accounts/{provider}} — unlink ({@code google} | {@code apple})</li>
 * </ul>
 */
@Tag(name = "Social accounts", description = "Comptes Google et Apple liés au compte")
@EntitlementExempt("authentification: comptes Google et Apple")
@RestController
@RequestMapping("/api/v1/auth/social/accounts")
public class SocialAccountController {

    private final SocialAccountUseCase socialAccounts;

    public SocialAccountController(SocialAccountUseCase socialAccounts) {
        this.socialAccounts = socialAccounts;
    }

    @Operation(summary = "List my linked Google / Apple accounts",
            description = "Story 19.3b — [{provider: GOOGLE|APPLE, label: « Google »|« Apple », linkedAt}], oldest first.")
    @GetMapping
    public ResponseEntity<ApiResponseWrapper<List<SocialAccountDto>>> list() {
        return ResponseEntity.ok(ApiResponseWrapper.ok(toDtos(socialAccounts.list(CurrentActor.actorId()))));
    }

    @Operation(summary = "Link a Google account",
            description = """
                Story 19.3b — verifies the Google ID token like /auth/social/google (nonce rules included) and links
                it to the signed-in account. Already linked to this account → 200 unchanged. Never changes the
                account's email or names. Answers the links.
                """)
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Linked — the account's links"),
        @ApiResponse(responseCode = "401", description = "SOCIAL_TOKEN_INVALID"),
        @ApiResponse(responseCode = "409", description = "SOCIAL_ACCOUNT_LINKED_ELSEWHERE or SOCIAL_PROVIDER_ALREADY_LINKED"),
        @ApiResponse(responseCode = "422", description = "Validation error"),
        @ApiResponse(responseCode = "429", description = "RATE_LIMIT_EXCEEDED")
    })
    @PostMapping("/google")
    public ResponseEntity<ApiResponseWrapper<List<SocialAccountDto>>> linkGoogle(
            @Valid @RequestBody GoogleLinkDto request, HttpServletRequest httpRequest) {
        return ResponseEntity.ok(ApiResponseWrapper.ok(toDtos(socialAccounts.link(new LinkCommand(
                CurrentActor.actorId(), SocialProvider.GOOGLE, request.idToken(), request.nonce(), null,
                ClientIps.of(httpRequest))))));
    }

    @Operation(summary = "Link an Apple account",
            description = """
                Story 19.3b — verifies the Apple identity token like /auth/social/apple (nonce rules included) and
                links it to the signed-in account. authorizationCode (optional): kept to revoke the Apple link when
                the account is deleted (App Store rule). Answers the links.
                """)
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Linked — the account's links"),
        @ApiResponse(responseCode = "401", description = "SOCIAL_TOKEN_INVALID"),
        @ApiResponse(responseCode = "409", description = "SOCIAL_ACCOUNT_LINKED_ELSEWHERE or SOCIAL_PROVIDER_ALREADY_LINKED"),
        @ApiResponse(responseCode = "422", description = "Validation error"),
        @ApiResponse(responseCode = "429", description = "RATE_LIMIT_EXCEEDED")
    })
    @PostMapping("/apple")
    public ResponseEntity<ApiResponseWrapper<List<SocialAccountDto>>> linkApple(
            @Valid @RequestBody AppleLinkDto request, HttpServletRequest httpRequest) {
        return ResponseEntity.ok(ApiResponseWrapper.ok(toDtos(socialAccounts.link(new LinkCommand(
                CurrentActor.actorId(), SocialProvider.APPLE, request.identityToken(), request.nonce(),
                request.authorizationCode(), ClientIps.of(httpRequest))))));
    }

    @Operation(summary = "Unlink a Google or Apple account",
            description = """
                Story 19.3b — {provider} = google | apple. Not linked → 204. Refused with 409 LAST_SIGN_IN_METHOD
                when the account would keep no password, phone, email nor other linked account. Unlinking Apple
                queues the revocation of its Apple token.
                """)
    @ApiResponses({
        @ApiResponse(responseCode = "204", description = "Unlinked (or was not linked)"),
        @ApiResponse(responseCode = "409", description = "LAST_SIGN_IN_METHOD")
    })
    @DeleteMapping("/{provider}")
    public ResponseEntity<Void> unlink(@PathVariable String provider) {
        socialAccounts.unlink(CurrentActor.actorId(), parseProvider(provider));
        return ResponseEntity.noContent().build();
    }

    /** {@code google} | {@code apple}, any case; anything else → 404 {@code NOT_FOUND}. */
    static SocialProvider parseProvider(String value) {
        return switch (value == null ? "" : value.toLowerCase(Locale.ROOT)) {
            case "google" -> SocialProvider.GOOGLE;
            case "apple" -> SocialProvider.APPLE;
            default -> throw new DomainException(ErrorCode.NOT_FOUND);
        };
    }

    static String label(SocialProvider provider) {
        return switch (provider) {
            case GOOGLE -> "Google";
            case APPLE -> "Apple";
        };
    }

    private static List<SocialAccountDto> toDtos(List<LinkedSocialAccount> accounts) {
        return accounts.stream()
                .map(a -> new SocialAccountDto(a.provider().name(), label(a.provider()), a.linkedAt()))
                .toList();
    }
}
