package com.keevo.messaging.notification.adapter.in.rest;

import com.keevo.messaging.notification.adapter.in.rest.dto.NotificationDto;
import com.keevo.messaging.notification.domain.port.in.NotificationInboxUseCase;
import com.keevo.shared.infrastructure.persistence.TenantContext;
import com.keevo.shared.infrastructure.security.CurrentActor;
import com.keevo.shared.infrastructure.web.ApiResponseWrapper;
import com.keevo.shared.infrastructure.web.InvalidRequestParameterException;
import com.keevo.shared.module.RequiresEntitlement;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Story 19.9 — the current user's notification inbox in the current tenant (every role: each one sees only their
 * own rows). Another user's notification is answered like a missing one (404 {@code NOTIFICATION_NOT_FOUND}).
 */
@Tag(name = "Notifications", description = "In-app notification inbox of the current user")
@RestController
@RequestMapping("/api/v1/notifications")
public class NotificationController {

    static final int MAX_PAGE_SIZE = 100;

    private final NotificationInboxUseCase inbox;

    public NotificationController(NotificationInboxUseCase inbox) {
        this.inbox = inbox;
    }

    /** GET /api/v1/notifications?page=&size= — own notifications, newest first (size 1–100, default 20). */
    @Operation(summary = "List my notifications, newest first")
    @RequiresEntitlement(value = "messaging", access = RequiresEntitlement.Access.READ)
    @GetMapping
    public ResponseEntity<ApiResponseWrapper<List<NotificationDto>>> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        if (page < 0) {
            throw new InvalidRequestParameterException("page", "page must be >= 0");
        }
        if (size < 1 || size > MAX_PAGE_SIZE) {
            throw new InvalidRequestParameterException("size", "size must be between 1 and " + MAX_PAGE_SIZE);
        }
        NotificationInboxUseCase.Page result = inbox.list(TenantContext.getCurrentTenant(), CurrentActor.actorId(),
                page, size);
        List<NotificationDto> items = result.items().stream().map(NotificationDto::from).toList();
        int totalPages = (int) ((result.totalElements() + size - 1) / size);
        return ResponseEntity.ok(ApiResponseWrapper.paginated(items, page, size, result.totalElements(),
                totalPages));
    }

    /** GET /api/v1/notifications/unread-count — {@code {count}} of my unread notifications. */
    @Operation(summary = "Count my unread notifications")
    @RequiresEntitlement(value = "messaging", access = RequiresEntitlement.Access.READ)
    @GetMapping("/unread-count")
    public ResponseEntity<ApiResponseWrapper<Map<String, Long>>> unreadCount() {
        long count = inbox.unreadCount(TenantContext.getCurrentTenant(), CurrentActor.actorId());
        return ResponseEntity.ok(ApiResponseWrapper.ok(Map.of("count", count)));
    }

    /** PATCH /api/v1/notifications/{id}/read — idempotent: 204 again on an already read notification. */
    @Operation(summary = "Mark one of my notifications as read")
    @RequiresEntitlement("messaging")
    @PatchMapping("/{id}/read")
    public ResponseEntity<Void> markRead(@PathVariable UUID id) {
        inbox.markRead(TenantContext.getCurrentTenant(), CurrentActor.actorId(), id);
        return ResponseEntity.noContent().build();
    }

    /** PATCH /api/v1/notifications/read-all — every unread notification of mine marked read. */
    @Operation(summary = "Mark all my notifications as read")
    @RequiresEntitlement("messaging")
    @PatchMapping("/read-all")
    public ResponseEntity<Void> markAllRead() {
        inbox.markAllRead(TenantContext.getCurrentTenant(), CurrentActor.actorId());
        return ResponseEntity.noContent().build();
    }
}
