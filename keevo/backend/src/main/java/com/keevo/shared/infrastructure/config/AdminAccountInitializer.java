package com.keevo.shared.infrastructure.config;

import com.keevo.identity.auth.adapter.out.persistence.entity.TenantJpaEntity;
import com.keevo.identity.auth.adapter.out.persistence.entity.UserJpaEntity;
import com.keevo.identity.auth.adapter.out.persistence.entity.UserTenantMembershipJpaEntity;
import com.keevo.identity.auth.adapter.out.persistence.jpa.TenantSpringRepository;
import com.keevo.identity.auth.adapter.out.persistence.jpa.UserSpringRepository;
import com.keevo.identity.auth.adapter.out.persistence.jpa.UserTenantMembershipSpringRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * AdminAccountInitializer — Bootstraps the SUPER_ADMIN account and its system tenant on startup.
 *
 * <p>How it works:
 * <ol>
 *   <li>Creates a system tenant (UUID {@value #ADMIN_TENANT_ID_STR}, schemaName {@code "public"})
 *       if it does not yet exist in {@code public.tenants}.</li>
 *   <li>Creates a SUPER_ADMIN user linked to that system tenant if the configured phone number
 *       is not already registered in {@code public.users}.</li>
 * </ol>
 *
 * <p>Idempotent — safe to run on every startup; both steps are guarded by existence checks.
 *
 * <p>Credentials are sourced from {@link AdminProperties} (env vars
 * {@code ADMIN_PHONE} / {@code ADMIN_PASSWORD}, with defaults from {@code application.yml}).
 *
 * <p>Schema routing: the system tenant uses schemaName {@code "public"}, which
 * {@link com.keevo.shared.infrastructure.persistence.SchemaAwareMultiTenantConnectionProvider}
 * treats as a no-op (no {@code SET search_path} override). Admin API calls therefore
 * operate on the public schema, which holds the {@code tenants} table.
 *
 * <p>Architecture: shared infrastructure config — triggered by Spring's
 * {@link ApplicationRunner} contract after the full application context is ready.
 */
@Component
public class AdminAccountInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AdminAccountInitializer.class);

    /** Fixed UUID reserved for the system admin tenant. Never changes across deployments. */
    static final String ADMIN_TENANT_ID_STR = "00000000-0000-0000-0000-000000000000";
    static final UUID ADMIN_TENANT_ID = UUID.fromString(ADMIN_TENANT_ID_STR);

    private static final String ADMIN_TENANT_CODE   = "KV-ADMIN";
    private static final String ADMIN_SCHEMA        = "public";
    private static final String ADMIN_STATUS        = "ACTIVE";
    private static final String ADMIN_PLAN_TYPE     = "PREMIUM";

    private final TenantSpringRepository              tenantSpringRepository;
    private final UserSpringRepository                userSpringRepository;
    private final UserTenantMembershipSpringRepository membershipSpringRepository;
    private final PasswordEncoder                     passwordEncoder;
    private final AdminProperties                     adminProperties;

    public AdminAccountInitializer(TenantSpringRepository tenantSpringRepository,
                                   UserSpringRepository userSpringRepository,
                                   UserTenantMembershipSpringRepository membershipSpringRepository,
                                   PasswordEncoder passwordEncoder,
                                   AdminProperties adminProperties) {
        this.tenantSpringRepository  = tenantSpringRepository;
        this.userSpringRepository    = userSpringRepository;
        this.membershipSpringRepository = membershipSpringRepository;
        this.passwordEncoder         = passwordEncoder;
        this.adminProperties         = adminProperties;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        ensureAdminTenant();
        ensureAdminUser();
    }

    // ── Private helpers ────────────────────────────────────────────────────

    private void ensureAdminTenant() {
        if (!tenantSpringRepository.existsById(ADMIN_TENANT_ID)) {
            TenantJpaEntity adminTenant = new TenantJpaEntity(
                    ADMIN_TENANT_ID,
                    ADMIN_TENANT_CODE,
                    ADMIN_SCHEMA,
                    ADMIN_STATUS,
                    ADMIN_PLAN_TYPE,
                    Integer.MAX_VALUE,   // maxStores   — no limit for admin
                    Integer.MAX_VALUE,   // maxProducts — no limit for admin
                    Integer.MAX_VALUE    // maxEmployees — no limit for admin
            );
            tenantSpringRepository.save(adminTenant);
            log.info("[AdminInit] System admin tenant created (id={})", ADMIN_TENANT_ID_STR);
        } else {
            log.debug("[AdminInit] System admin tenant already exists — skipping");
        }
    }

    private void ensureAdminUser() {
        String phone = adminProperties.phone();
        UUID adminUserId;
        var existingUser = userSpringRepository.findByPhoneNumber(phone);
        if (existingUser.isEmpty()) {
            String hash = passwordEncoder.encode(adminProperties.password());
            adminUserId = UUID.randomUUID();
            // 7-arg constructor: no tenantId (Story 1.7 — tenantId removed from User)
            UserJpaEntity adminUser = new UserJpaEntity(
                    adminUserId,
                    phone,
                    hash,
                    "SUPER_ADMIN",
                    true,
                    0,
                    null
            );
            userSpringRepository.save(adminUser);
            log.info("[AdminInit] Super admin account created for phone {}",
                    maskPhone(phone));
        } else {
            adminUserId = existingUser.get().getId();
            log.debug("[AdminInit] Super admin account already exists for phone {} — skipping",
                    maskPhone(phone));
        }

        // Always verify membership exists — crash-recovery guard:
        // if app crashed after user creation but before membership creation on a previous run,
        // the next startup would skip the whole block (user exists) leaving admin without membership.
        boolean hasMembership = membershipSpringRepository
                .findByUserIdAndTenantId(adminUserId, ADMIN_TENANT_ID).isPresent();
        if (!hasMembership) {
            membershipSpringRepository.save(new UserTenantMembershipJpaEntity(
                    UUID.randomUUID(), adminUserId, ADMIN_TENANT_ID, "SUPER_ADMIN", true));
            log.info("[AdminInit] SUPER_ADMIN membership created for userId={}", adminUserId);
        }
    }

    /** Masks all but the last 4 digits of the phone number to avoid PII in logs. */
    private static String maskPhone(String phone) {
        return phone != null && phone.length() > 4
                ? "***" + phone.substring(phone.length() - 4)
                : "***";
    }
}
