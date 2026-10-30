package com.keevo.admin.notification.adapter.in.rest;

import com.keevo.admin.notification.adapter.in.rest.dto.AnnouncementDto;
import com.keevo.admin.notification.domain.port.in.ListAnnouncementsUseCase;
import com.keevo.identity.api.Role;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import com.keevo.shared.infrastructure.persistence.TenantContext;
import com.keevo.shared.infrastructure.security.CurrentActor;
import com.keevo.shared.infrastructure.web.ApiResponseWrapper;
import com.keevo.shared.module.EntitlementExempt;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * AnnouncementController — Keevo announcements (global pushes SENT) reaching the caller's company, so
 * the merchant app lists them even when the push was never received or tapped (Story 9-5, FR82).
 * OWNER / EMPLOYEE only; the company always comes from the JWT (tenant context), never the request.
 */
@EntitlementExempt("core: annonces de la plateforme")
@RestController
@RequestMapping("/api/v1/notifications/announcements")
public class AnnouncementController {

    private final ListAnnouncementsUseCase listAnnouncements;

    public AnnouncementController(ListAnnouncementsUseCase listAnnouncements) {
        this.listAnnouncements = listAnnouncements;
    }

    /** @param since ISO-8601 date-time (exclusive lower bound is not needed: upsert by id), absent = 90 days */
    @GetMapping
    public ResponseEntity<ApiResponseWrapper<List<AnnouncementDto>>> list(
            @RequestParam(required = false) String since) {
        if (!CurrentActor.hasRole(Role.OWNER) && !CurrentActor.hasRole(Role.EMPLOYEE)) {
            throw new DomainException(ErrorCode.FORBIDDEN, "Merchant users only");
        }
        String schema = TenantContext.getCurrentTenant();
        return ResponseEntity.ok(ApiResponseWrapper.ok(listAnnouncements.announcements(schema, since).stream()
                .map(AnnouncementDto::from)
                .toList()));
    }
}
