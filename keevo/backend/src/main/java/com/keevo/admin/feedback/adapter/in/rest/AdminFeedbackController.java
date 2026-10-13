package com.keevo.admin.feedback.adapter.in.rest;

import com.keevo.admin.AdminLabels;
import com.keevo.admin.feedback.application.service.AdminFeedbackLabelService;
import com.keevo.admin.feedback.application.service.AdminFeedbackLabelService.FeedbackLabels;
import com.keevo.feedback.feedback.domain.model.Feedback;
import com.keevo.feedback.feedback.domain.port.in.HandleFeedbackUseCase;
import com.keevo.feedback.feedback.domain.port.in.ListFeedbackUseCase;
import com.keevo.feedback.feedback.domain.port.in.ListFeedbackUseCase.ListFeedbackQuery;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import com.keevo.shared.infrastructure.security.CurrentActor;
import com.keevo.shared.infrastructure.web.ApiResponseWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * AdminFeedbackController — SUPER_ADMIN « Retours utilisateurs » inbox (Story 14.5 FR92, spec 9-1
 * completion).
 *
 * <p>Inherits the {@code /api/v1/admin/**} {@code SUPER_ADMIN} filter from SecurityConfig, plus the
 * class-level {@code @PreAuthorize} and an explicit {@link CurrentActor#requireSuperAdmin()} per method.
 */
@RestController
@RequestMapping("/api/v1/admin/feedback")
@PreAuthorize("hasRole('SUPER_ADMIN')")
public class AdminFeedbackController {

    private static final Logger log = LoggerFactory.getLogger(AdminFeedbackController.class);

    private static final Set<String> PRIORITIES = Set.of("HIGH", "NORMAL");
    /** Types sent by the app (feedback_type.dart). */
    private static final Set<String> TYPES = Set.of("SUGGESTION", "BUG", "PRAISE");
    private static final int SCREEN_CONTEXT_MAX = 200;

    private final ListFeedbackUseCase listFeedbackUseCase;
    private final HandleFeedbackUseCase handleFeedbackUseCase;
    private final AdminFeedbackLabelService labelService;

    public AdminFeedbackController(ListFeedbackUseCase listFeedbackUseCase,
                                   HandleFeedbackUseCase handleFeedbackUseCase,
                                   AdminFeedbackLabelService labelService) {
        this.listFeedbackUseCase = listFeedbackUseCase;
        this.handleFeedbackUseCase = handleFeedbackUseCase;
        this.labelService = labelService;
    }

    /**
     * @param handled {@code false} (default: not handled yet), {@code true} (handled only) or
     *                {@code all}; any other value → 422 VALIDATION_ERROR
     * @param priority HIGH | NORMAL (case-insensitive); any other value → 422 VALIDATION_ERROR
     */
    @GetMapping
    public ResponseEntity<ApiResponseWrapper<List<AdminFeedbackDto>>> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String priority,
            @RequestParam(required = false) String screenContext,
            @RequestParam(required = false) String type,
            @RequestParam(defaultValue = "false") String handled) {

        CurrentActor.requireSuperAdmin();

        Page<Feedback> result = listFeedbackUseCase.list(new ListFeedbackQuery(
                page, size, normalizePriority(priority), normalizeScreenContext(screenContext),
                normalizeType(type), parseHandled(handled)));

        Map<UUID, FeedbackLabels> labels = labelService.labels(result.getContent());
        List<AdminFeedbackDto> dtos = result.getContent().stream()
                .map(f -> AdminFeedbackDto.from(f, labels.get(f.getId())))
                .toList();

        return ResponseEntity.ok(ApiResponseWrapper.paginated(dtos,
                result.getNumber(), result.getSize(),
                result.getTotalElements(), result.getTotalPages()));
    }

    /**
     * Count only (no labels, no rows) — the dashboard sidebar badge polls
     * {@code ?priority=HIGH&handled=false}. Same filters and validation as the list.
     */
    @GetMapping("/count")
    public ResponseEntity<ApiResponseWrapper<Long>> count(
            @RequestParam(required = false) String priority,
            @RequestParam(required = false) String screenContext,
            @RequestParam(required = false) String type,
            @RequestParam(defaultValue = "false") String handled) {
        CurrentActor.requireSuperAdmin();
        return ResponseEntity.ok(ApiResponseWrapper.ok(listFeedbackUseCase.count(new ListFeedbackQuery(
                0, 1, normalizePriority(priority), normalizeScreenContext(screenContext),
                normalizeType(type), parseHandled(handled)))));
    }

    /** Marks a feedback « Traité » (idempotent: first handler kept); unknown id → 404 NOT_FOUND. */
    @PostMapping("/{id}/handle")
    public ResponseEntity<ApiResponseWrapper<AdminFeedbackDto>> handle(@PathVariable UUID id) {
        CurrentActor.requireSuperAdmin();
        Feedback updated = handleFeedbackUseCase.markHandled(id, CurrentActor.actorId());
        return ResponseEntity.ok(ApiResponseWrapper.ok(withLabels(updated)));
    }

    /** Puts a feedback back in the inbox; unknown id → 404 NOT_FOUND. */
    @PostMapping("/{id}/unhandle")
    public ResponseEntity<ApiResponseWrapper<AdminFeedbackDto>> unhandle(@PathVariable UUID id) {
        CurrentActor.requireSuperAdmin();
        Feedback updated = handleFeedbackUseCase.markUnhandled(id, CurrentActor.actorId());
        return ResponseEntity.ok(ApiResponseWrapper.ok(withLabels(updated)));
    }

    /**
     * Labels after a state change that is already committed: a label failure must not turn the
     * successful action into a 500 — the DTO then carries the fallback labels.
     */
    private AdminFeedbackDto withLabels(Feedback f) {
        FeedbackLabels labels = null;
        try {
            labels = labelService.labels(List.of(f)).get(f.getId());
        } catch (RuntimeException e) {
            log.warn("Feedback {}: labels unavailable after the action ({})", f.getId(), e.getClass().getSimpleName());
        }
        return AdminFeedbackDto.from(f, labels);
    }

    private static String normalizeType(String raw) {
        String t = blankToNull(raw);
        if (t == null) return null;
        t = t.toUpperCase(Locale.ROOT);
        if (!TYPES.contains(t)) {
            throw new DomainException(ErrorCode.VALIDATION_ERROR, "type must be SUGGESTION, BUG or PRAISE");
        }
        return t;
    }

    private static String normalizeScreenContext(String raw) {
        String c = blankToNull(raw);
        if (c != null && c.length() > SCREEN_CONTEXT_MAX) {
            throw new DomainException(ErrorCode.VALIDATION_ERROR,
                    "screenContext must be at most " + SCREEN_CONTEXT_MAX + " characters");
        }
        return c;
    }

    private static String normalizePriority(String raw) {
        String p = blankToNull(raw);
        if (p == null) return null;
        p = p.toUpperCase(Locale.ROOT);
        if (!PRIORITIES.contains(p)) {
            throw new DomainException(ErrorCode.VALIDATION_ERROR, "priority must be HIGH or NORMAL");
        }
        return p;
    }

    private static Boolean parseHandled(String raw) {
        String h = blankToNull(raw);
        if (h == null) return Boolean.FALSE;
        return switch (h.toLowerCase(Locale.ROOT)) {
            case "false" -> Boolean.FALSE;
            case "true" -> Boolean.TRUE;
            case "all" -> null;
            default -> throw new DomainException(ErrorCode.VALIDATION_ERROR, "handled must be true, false or all");
        };
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    /**
     * One feedback of the inbox. {@code id} only serves the handle/unhandle actions; the company
     * schema name and the user id are never sent — {@code tenantName} / {@code userLabel} instead.
     */
    public record AdminFeedbackDto(
            UUID id, String type, String description,
            String appVersion, String platform, String screenContext,
            String submittedAt, String priority,
            String tenantName, String userLabel,
            boolean handled, String handledAt
    ) {
        public static AdminFeedbackDto from(Feedback f, FeedbackLabels labels) {
            return new AdminFeedbackDto(
                    f.getId(), f.getType(), f.getDescription(),
                    f.getAppVersion(), f.getPlatform(), f.getScreenContext(),
                    f.getSubmittedAt().toString(), f.getPriority(),
                    labels != null ? labels.tenantName() : AdminLabels.DELETED_TENANT,
                    labels != null ? labels.userLabel() : AdminLabels.UNKNOWN_USER,
                    f.isHandled(),
                    f.getHandledAt() != null ? f.getHandledAt().toString() : null
            );
        }
    }
}
