package com.keevo.admin.notification.adapter.in.rest;

import com.keevo.admin.notification.adapter.in.rest.dto.GlobalNotificationDto;
import com.keevo.admin.notification.adapter.in.rest.dto.GlobalNotificationRequestDto;
import com.keevo.admin.notification.adapter.in.rest.dto.GlobalNotificationScheduledDto;
import com.keevo.admin.notification.adapter.in.rest.dto.PushTenantDto;
import com.keevo.admin.notification.domain.port.in.CancelGlobalNotificationUseCase;
import com.keevo.admin.notification.domain.port.in.EstimateGlobalNotificationUseCase;
import com.keevo.admin.notification.domain.port.in.ListGlobalNotificationsUseCase;
import com.keevo.admin.notification.domain.port.in.ScheduleGlobalNotificationCommand;
import com.keevo.admin.notification.domain.port.in.ScheduleGlobalNotificationUseCase;
import com.keevo.admin.notification.domain.port.in.SearchPushTenantsUseCase;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import com.keevo.shared.infrastructure.security.CurrentActor;
import com.keevo.shared.infrastructure.web.ApiResponseWrapper;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * AdminGlobalNotificationController — global push notifications of the super admin (Story 9-5, FR82).
 *
 * <p>Inherits the {@code /api/v1/admin/**} {@code SUPER_ADMIN} filter from SecurityConfig, plus the
 * class-level {@code @PreAuthorize} and an explicit {@link CurrentActor#requireSuperAdmin()} per method.
 */
@RestController
@RequestMapping("/api/v1/admin/notifications/global")
@PreAuthorize("hasRole('SUPER_ADMIN')")
public class AdminGlobalNotificationController {

    private final ScheduleGlobalNotificationUseCase scheduleUseCase;
    private final ListGlobalNotificationsUseCase listUseCase;
    private final CancelGlobalNotificationUseCase cancelUseCase;
    private final EstimateGlobalNotificationUseCase estimateUseCase;
    private final SearchPushTenantsUseCase searchTenantsUseCase;

    public AdminGlobalNotificationController(ScheduleGlobalNotificationUseCase scheduleUseCase,
                                             ListGlobalNotificationsUseCase listUseCase,
                                             CancelGlobalNotificationUseCase cancelUseCase,
                                             EstimateGlobalNotificationUseCase estimateUseCase,
                                             SearchPushTenantsUseCase searchTenantsUseCase) {
        this.scheduleUseCase = scheduleUseCase;
        this.listUseCase = listUseCase;
        this.cancelUseCase = cancelUseCase;
        this.estimateUseCase = estimateUseCase;
        this.searchTenantsUseCase = searchTenantsUseCase;
    }

    /** Schedules a global push (now when {@code scheduledAt} is null) → 200 {@code { id, recipientEstimate }}. */
    @PostMapping
    public ResponseEntity<ApiResponseWrapper<GlobalNotificationScheduledDto>> schedule(
            @RequestBody(required = false) GlobalNotificationRequestDto body) {
        CurrentActor.requireSuperAdmin();
        if (body == null) {
            throw new DomainException(ErrorCode.VALIDATION_ERROR, "Missing body", Map.of(
                    "title", "Le titre est obligatoire",
                    "body", "Le message est obligatoire",
                    "target", "La cible est obligatoire"));
        }
        ScheduleGlobalNotificationUseCase.Scheduled scheduled = scheduleUseCase.schedule(
                new ScheduleGlobalNotificationCommand(body.title(), body.body(), body.target(), body.targetTenantId(),
                        body.scheduledAt(), CurrentActor.actorId()));
        return ResponseEntity.ok(ApiResponseWrapper.ok(
                new GlobalNotificationScheduledDto(scheduled.id(), scheduled.recipientEstimate())));
    }

    /** History of the last 90 days, newest first. */
    @GetMapping
    public ResponseEntity<ApiResponseWrapper<List<GlobalNotificationDto>>> history() {
        CurrentActor.requireSuperAdmin();
        return ResponseEntity.ok(ApiResponseWrapper.ok(
                listUseCase.history().stream().map(GlobalNotificationDto::from).toList()));
    }

    /** Cancels a SCHEDULED push → 204; 409 INVALID_STATE once sending started, 404 unknown id. */
    @PostMapping("/{id}/cancel")
    public ResponseEntity<Void> cancel(@PathVariable String id) {
        CurrentActor.requireSuperAdmin();
        cancelUseCase.cancel(parseId(id), CurrentActor.actorId());
        return ResponseEntity.noContent().build();
    }

    /** Devices a target currently reaches. */
    @GetMapping("/estimate")
    public ResponseEntity<ApiResponseWrapper<Long>> estimate(@RequestParam(required = false) String target,
                                                             @RequestParam(required = false) String targetTenantId) {
        CurrentActor.requireSuperAdmin();
        return ResponseEntity.ok(ApiResponseWrapper.ok(estimateUseCase.estimate(target, targetTenantId)));
    }

    /** Companies a push can target (not deleted / suspended), name match, at most 20 — picker « Un commerce ». */
    @GetMapping("/tenants")
    public ResponseEntity<ApiResponseWrapper<List<PushTenantDto>>> tenants(
            @RequestParam(required = false) String search) {
        CurrentActor.requireSuperAdmin();
        return ResponseEntity.ok(ApiResponseWrapper.ok(searchTenantsUseCase.searchTenants(search).stream()
                .map(t -> new PushTenantDto(t.id(), t.name()))
                .toList()));
    }

    private static UUID parseId(String raw) {
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException e) {
            throw new DomainException(ErrorCode.NOT_FOUND, "Annonce introuvable");
        }
    }
}
