package com.keevo.identity.auth.application.service;

import com.keevo.identity.auth.domain.model.*;
import com.keevo.identity.auth.domain.port.out.TenantRepository;
import com.keevo.identity.auth.domain.port.out.UserRepository;
import com.keevo.messaging.whatsapp.domain.port.out.WhatsAppPort;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Locale;
import java.util.UUID;

/**
 * AccountDeletionService — Handles OWNER self-service account deletion
 * request and cancellation (Story 14.5, FR91).
 *
 * <p>Uses {@link TenantRepository} (hexagonal port) for tenant persistence,
 * {@link UserRepository} to retrieve OWNER phone for WhatsApp notification,
 * and {@link ApplicationEventPublisher} for audit events.
 */
@Service
@Transactional
public class AccountDeletionService {

    private static final Logger log = LoggerFactory.getLogger(AccountDeletionService.class);
    private static final DateTimeFormatter DATE_FMT =
            DateTimeFormatter.ofPattern("dd/MM/yyyy", Locale.FRENCH);

    private final TenantRepository tenantRepository;
    private final UserRepository userRepository;
    private final WhatsAppPort whatsAppPort;
    private final ApplicationEventPublisher eventPublisher;

    public AccountDeletionService(TenantRepository tenantRepository,
                                   UserRepository userRepository,
                                   WhatsAppPort whatsAppPort,
                                   ApplicationEventPublisher eventPublisher) {
        this.tenantRepository = tenantRepository;
        this.userRepository = userRepository;
        this.whatsAppPort = whatsAppPort;
        this.eventPublisher = eventPublisher;
    }

    /**
     * Request account deletion — sets tenant to DELETION_PENDING with 30-day grace period.
     *
     * @param actorId   UUID of the authenticated OWNER requesting deletion
     * @param tenantId  schema name (e.g. "kv_abc123") resolved from JWT / TenantContext
     */
    public void requestDeletion(UUID actorId, String tenantId) {
        Tenant tenant = tenantRepository.findBySchemaName(tenantId)
                .orElseThrow(() -> new DomainException(ErrorCode.TENANT_NOT_FOUND,
                        "Tenant not found: " + tenantId));

        if (tenant.getStatus() == TenantStatus.DELETION_PENDING) {
            throw new DomainException(ErrorCode.DELETION_ALREADY_REQUESTED,
                    "Deletion already requested for tenant: " + tenantId);
        }

        Instant deletionDate = Instant.now().plus(30, ChronoUnit.DAYS);
        Tenant updated = tenant.withStatus(TenantStatus.DELETION_PENDING, deletionDate);
        tenantRepository.save(updated);

        // Publish audit event
        AccountDeletionRequestedEvent event =
                AccountDeletionRequestedEvent.of(tenant.getId(), actorId, deletionDate);
        eventPublisher.publishEvent(event);

        // Send WhatsApp confirmation to OWNER — after TX commits to avoid holding DB connection during network I/O
        final String ownerPhone = userRepository.findById(actorId).map(User::getPhoneNumber).orElse(null);
        final String tenantName = tenant.getName();
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    sendDeletionConfirmationWhatsApp(ownerPhone, deletionDate, tenantName);
                }
            });
        } else {
            // Fallback if no TX active (shouldn't happen in practice)
            sendDeletionConfirmationWhatsApp(ownerPhone, deletionDate, tenantName);
        }

        log.info("Account deletion requested: tenantId={}, actorId={}, scheduledDeletion={}",
                tenant.getId(), actorId, deletionDate);
    }

    /**
     * Cancel a pending account deletion — restores tenant to ACTIVE.
     *
     * @param actorId   UUID of the authenticated OWNER cancelling deletion
     * @param tenantId  schema name (e.g. "kv_abc123")
     */
    public void cancelDeletion(UUID actorId, String tenantId) {
        Tenant tenant = tenantRepository.findBySchemaName(tenantId)
                .orElseThrow(() -> new DomainException(ErrorCode.TENANT_NOT_FOUND,
                        "Tenant not found: " + tenantId));

        if (tenant.getStatus() != TenantStatus.DELETION_PENDING) {
            throw new DomainException(ErrorCode.DELETION_NOT_PENDING,
                    "No pending deletion for tenant: " + tenantId);
        }

        Tenant updated = tenant.withStatus(TenantStatus.ACTIVE, null);
        tenantRepository.save(updated);

        // Publish audit event
        AccountDeletionCancelledEvent event =
                AccountDeletionCancelledEvent.of(tenant.getId(), actorId);
        eventPublisher.publishEvent(event);

        log.info("Account deletion cancelled: tenantId={}, actorId={}",
                tenant.getId(), actorId);
    }

    private void sendDeletionConfirmationWhatsApp(String ownerPhone, Instant deletionDate,
                                                   String tenantName) {
        try {
            if (ownerPhone == null || ownerPhone.isBlank()) {
                log.warn("Cannot send deletion WhatsApp — OWNER phone not found");
                return;
            }
            String formattedDate = ZonedDateTime.ofInstant(deletionDate, ZoneId.of("Africa/Kinshasa"))
                    .format(DATE_FMT);
            String message = String.format(
                    "Votre demande de suppression a été enregistrée. " +
                    "Toutes vos données seront supprimées le %s. " +
                    "Pour annuler, accédez à Paramètres > Mon Compte dans l'app.",
                    formattedDate);
            whatsAppPort.sendReport(ownerPhone, message);
        } catch (Exception e) {
            log.error("Failed to send deletion confirmation WhatsApp: phone={}", ownerPhone, e);
            // Non-blocking — WhatsApp failure does not abort the deletion request
        }
    }
}
