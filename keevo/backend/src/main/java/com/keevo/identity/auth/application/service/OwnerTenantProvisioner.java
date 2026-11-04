package com.keevo.identity.auth.application.service;

import com.keevo.identity.auth.domain.model.AuthTokens;
import com.keevo.identity.auth.domain.model.DeviceInfo;
import com.keevo.identity.auth.domain.model.Tenant;
import com.keevo.identity.auth.domain.model.UserTenantMembership;
import com.keevo.identity.auth.domain.port.in.RegistrationResult;
import com.keevo.identity.auth.domain.port.out.TenantSchemaPort;
import com.keevo.identity.auth.domain.port.out.UserMembershipRepository;
import com.keevo.shared.event.catalog.auth.UserRegisteredEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.util.UUID;
import java.util.function.Supplier;

/**
 * Story 19.2 — provisions a company for its owner: tenant (schema, trial plan), OWNER membership, owner role in the
 * tenant schema, session (access + refresh token), {@link UserRegisteredEvent}. Extracted from
 * {@link RegistrationService} so {@code /auth/register} and {@code /auth/create-company} share one path.
 *
 * <p>Runs inside the caller's transaction (no {@code @Transactional} here): any failure rolls everything back.
 */
@Component
public class OwnerTenantProvisioner {

    private static final String OWNER = "OWNER";

    private final UserMembershipRepository userMembershipRepository;
    private final TenantFactory tenantFactory;
    private final SessionTokenIssuer sessionTokenIssuer;
    private final ApplicationEventPublisher eventPublisher;
    private final TenantSchemaPort tenantSchemaPort;

    public OwnerTenantProvisioner(UserMembershipRepository userMembershipRepository,
                                  TenantFactory tenantFactory,
                                  SessionTokenIssuer sessionTokenIssuer,
                                  ApplicationEventPublisher eventPublisher,
                                  TenantSchemaPort tenantSchemaPort) {
        this.userMembershipRepository = userMembershipRepository;
        this.tenantFactory = tenantFactory;
        this.sessionTokenIssuer = sessionTokenIssuer;
        this.eventPublisher = eventPublisher;
        this.tenantSchemaPort = tenantSchemaPort;
    }

    /**
     * @param owner  supplies the owner's id once the tenant exists (registration saves the new user there, so a failed
     *               provisioning never saves it)
     * @param device the app's device (null or {@link DeviceInfo#NONE} = none)
     */
    public RegistrationResult provision(Supplier<UUID> owner, DeviceInfo device) {
        // 1. Provision tenant (schema creation + Flyway migrations, trial plan) — TenantFactory
        Tenant tenant = tenantFactory.create();

        // 2. Owner account (saved by the caller's supplier when new)
        UUID ownerId = owner.get();

        // 3. OWNER membership in the new tenant (Story 1.7)
        userMembershipRepository.save(UserTenantMembership.create(ownerId, tenant.getId(), OWNER));

        // 4. OWNER role in the tenant schema (user_roles) — same transaction
        tenantSchemaPort.assignOwnerRole(tenant.getSchemaName(), ownerId);

        // 5. Session (access + refresh token) like a login: without a refresh token the app cannot renew it
        AuthTokens tokens = device == null || device.isEmpty()
                ? sessionTokenIssuer.issue(ownerId, tenant, OWNER).tokens()
                : sessionTokenIssuer.issue(ownerId, tenant, OWNER, null, device).tokens();

        // 6. Domain event (Observer → AuditEventListener)
        eventPublisher.publishEvent(
                UserRegisteredEvent.of(ownerId, tenant.getId(), tenant.getCode(), tenant.getSchemaName()));

        return new RegistrationResult(
                tenant.getCode(),
                tokens.accessToken(),
                ownerId.toString(),
                tenant.getId().toString(),
                tokens.refreshToken());
    }
}
