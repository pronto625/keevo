package com.keevo.feedback.feedback.adapter.in.rest;

import com.keevo.feedback.feedback.domain.model.Feedback;
import com.keevo.feedback.feedback.domain.port.in.SubmitFeedbackUseCase;
import com.keevo.shared.infrastructure.web.ApiResponseWrapper;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

/**
 * FeedbackController — REST endpoint for user feedback submission (Story 14.5, FR92).
 *
 * <p>Accessible to ALL authenticated roles (OWNER + EMPLOYEE).
 * tenantId/userId/submittedAt are derived server-side from the security context.
 */
@RestController
@RequestMapping("/api/v1/feedback")
public class FeedbackController {

    private final SubmitFeedbackUseCase submitFeedbackUseCase;

    public FeedbackController(SubmitFeedbackUseCase submitFeedbackUseCase) {
        this.submitFeedbackUseCase = submitFeedbackUseCase;
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('OWNER', 'EMPLOYEE')")
    public ResponseEntity<ApiResponseWrapper<Map<String, String>>> submit(
            @Valid @RequestBody SubmitFeedbackRequest request) {

        UUID userId = (UUID) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        String tenantId = com.keevo.shared.infrastructure.persistence.TenantContext.getCurrentTenant();

        var command = new SubmitFeedbackUseCase.SubmitFeedbackCommand(
                request.type(),
                request.description(),
                request.appVersion(),
                request.platform(),
                request.screenContext(),
                tenantId,
                userId
        );

        Feedback saved = submitFeedbackUseCase.submit(command);

        return ResponseEntity.ok(ApiResponseWrapper.ok(
                Map.of("id", saved.getId().toString(), "message", "Feedback enregistré")));
    }

    public record SubmitFeedbackRequest(
            @NotBlank @Size(max = 30) String type,
            @NotBlank @Size(min = 10, max = 500) String description,
            @Size(max = 20) String appVersion,
            @Size(max = 20) String platform,
            @Size(max = 200) String screenContext
    ) {}
}
