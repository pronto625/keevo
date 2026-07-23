package com.keevo.admin.feedback.adapter.in.rest;

import com.keevo.feedback.feedback.domain.model.Feedback;
import com.keevo.feedback.feedback.domain.port.out.FeedbackRepository;
import com.keevo.shared.infrastructure.web.ApiResponseWrapper;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * AdminFeedbackController — SUPER_ADMIN endpoint for listing feedback (Story 14.5, FR92).
 *
 * <p>Inherits the {@code /api/v1/admin/**} {@code SUPER_ADMIN} filter from SecurityConfig.
 */
@RestController
@RequestMapping("/api/v1/admin/feedback")
@PreAuthorize("hasRole('SUPER_ADMIN')")
public class AdminFeedbackController {

    private final FeedbackRepository feedbackRepository;

    public AdminFeedbackController(FeedbackRepository feedbackRepository) {
        this.feedbackRepository = feedbackRepository;
    }

    @GetMapping
    public ResponseEntity<ApiResponseWrapper<List<AdminFeedbackDto>>> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String priority,
            @RequestParam(required = false) String screenContext) {

        PageRequest pageable = PageRequest.of(page, Math.min(size, 100));
        Page<Feedback> result;

        if (priority != null) {
            result = feedbackRepository.findByPriority(priority, pageable);
        } else if (screenContext != null) {
            result = feedbackRepository.findByScreenContext(screenContext, pageable);
        } else {
            result = feedbackRepository.findAll(pageable);
        }

        List<AdminFeedbackDto> dtos = result.getContent().stream()
                .map(AdminFeedbackDto::from)
                .toList();

        return ResponseEntity.ok(ApiResponseWrapper.paginated(dtos,
                result.getNumber(), result.getSize(),
                result.getTotalElements(), result.getTotalPages()));
    }

    public record AdminFeedbackDto(
            UUID id, String type, String description,
            String tenantId, UUID userId,
            String appVersion, String platform, String screenContext,
            String submittedAt, String priority
    ) {
        public static AdminFeedbackDto from(Feedback f) {
            return new AdminFeedbackDto(
                    f.getId(), f.getType(), f.getDescription(),
                    f.getTenantId(), f.getUserId(),
                    f.getAppVersion(), f.getPlatform(), f.getScreenContext(),
                    f.getSubmittedAt().toString(), f.getPriority()
            );
        }
    }
}
