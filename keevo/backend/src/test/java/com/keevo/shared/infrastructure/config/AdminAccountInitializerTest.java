package com.keevo.shared.infrastructure.config;

import com.keevo.identity.auth.adapter.out.persistence.entity.TenantJpaEntity;
import com.keevo.identity.auth.adapter.out.persistence.entity.UserJpaEntity;
import com.keevo.identity.auth.adapter.out.persistence.jpa.TenantSpringRepository;
import com.keevo.identity.auth.adapter.out.persistence.jpa.UserSpringRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.ApplicationArguments;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * AdminAccountInitializerTest — Unit tests for the SUPER_ADMIN bootstrap logic.
 *
 * <p>Verifies idempotency: both tenant and user creation are gated by existence checks
 * and must not run twice on repeated startups.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AdminAccountInitializer")
class AdminAccountInitializerTest {

    @Mock TenantSpringRepository tenantRepo;
    @Mock UserSpringRepository   userRepo;
    @Mock PasswordEncoder        passwordEncoder;
    @Mock ApplicationArguments   args;

    private AdminAccountInitializer initializer;

    @BeforeEach
    void setUp() {
        AdminProperties props = new AdminProperties("+237600000000", "Admin@1234!");
        initializer = new AdminAccountInitializer(tenantRepo, userRepo, passwordEncoder, props);
    }

    // ── First-boot: nothing exists ─────────────────────────────────────────

    @Nested
    @DisplayName("first boot — neither tenant nor user exist")
    class FirstBoot {

        @BeforeEach
        void stubEmpty() {
            when(tenantRepo.existsById(AdminAccountInitializer.ADMIN_TENANT_ID)).thenReturn(false);
            when(userRepo.findByPhoneNumber(anyString())).thenReturn(Optional.empty());
            when(passwordEncoder.encode(anyString())).thenReturn("$2a$10$hashedPassword");
            when(tenantRepo.save(any(TenantJpaEntity.class))).thenAnswer(inv -> inv.getArgument(0));
            when(userRepo.save(any(UserJpaEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        }

        @Test
        @DisplayName("saves system admin tenant exactly once")
        void savesTenant() {
            initializer.run(args);
            verify(tenantRepo, times(1)).save(any(TenantJpaEntity.class));
        }

        @Test
        @DisplayName("saved tenant has correct schema name and status")
        void tenantHasCorrectAttributes() {
            initializer.run(args);
            ArgumentCaptor<TenantJpaEntity> captor = ArgumentCaptor.forClass(TenantJpaEntity.class);
            verify(tenantRepo).save(captor.capture());
            TenantJpaEntity saved = captor.getValue();
            assertThat(saved.getSchemaName()).isEqualTo("public");
            assertThat(saved.getStatus()).isEqualTo("ACTIVE");
            assertThat(saved.getCode()).isEqualTo("KV-ADMIN");
        }

        @Test
        @DisplayName("saves SUPER_ADMIN user exactly once")
        void savesUser() {
            initializer.run(args);
            verify(userRepo, times(1)).save(any(UserJpaEntity.class));
        }

        @Test
        @DisplayName("saved user has SUPER_ADMIN role linked to admin tenant")
        void userHasCorrectAttributes() {
            initializer.run(args);
            ArgumentCaptor<UserJpaEntity> captor = ArgumentCaptor.forClass(UserJpaEntity.class);
            verify(userRepo).save(captor.capture());
            UserJpaEntity saved = captor.getValue();
            assertThat(saved.getRole()).isEqualTo("SUPER_ADMIN");
            assertThat(saved.getTenantId()).isEqualTo(AdminAccountInitializer.ADMIN_TENANT_ID);
            assertThat(saved.getPhoneNumber()).isEqualTo("+237600000000");
            assertThat(saved.isActive()).isTrue();
        }

        @Test
        @DisplayName("password is hashed before storage")
        void passwordIsHashed() {
            initializer.run(args);
            verify(passwordEncoder).encode("Admin@1234!");
            ArgumentCaptor<UserJpaEntity> captor = ArgumentCaptor.forClass(UserJpaEntity.class);
            verify(userRepo).save(captor.capture());
            assertThat(captor.getValue().getPasswordHash()).isEqualTo("$2a$10$hashedPassword");
        }
    }

    // ── Subsequent boots: everything already exists ─────────────────────────

    @Nested
    @DisplayName("subsequent boot — tenant and user already exist")
    class SubsequentBoot {

        @BeforeEach
        void stubPresent() {
            when(tenantRepo.existsById(AdminAccountInitializer.ADMIN_TENANT_ID)).thenReturn(true);
            when(userRepo.findByPhoneNumber(anyString()))
                    .thenReturn(Optional.of(mock(UserJpaEntity.class)));
        }

        @Test
        @DisplayName("does not save tenant again")
        void doesNotSaveTenant() {
            initializer.run(args);
            verify(tenantRepo, never()).save(any());
        }

        @Test
        @DisplayName("does not save user again")
        void doesNotSaveUser() {
            initializer.run(args);
            verify(userRepo, never()).save(any());
        }

        @Test
        @DisplayName("does not encode password again")
        void doesNotEncodePassword() {
            initializer.run(args);
            verify(passwordEncoder, never()).encode(anyString());
        }
    }

    // ── Partial state: tenant exists but user missing ────────────────────────

    @Nested
    @DisplayName("partial state — tenant exists, user missing")
    class PartialState {

        @BeforeEach
        void stubPartial() {
            when(tenantRepo.existsById(AdminAccountInitializer.ADMIN_TENANT_ID)).thenReturn(true);
            when(userRepo.findByPhoneNumber(anyString())).thenReturn(Optional.empty());
            when(passwordEncoder.encode(anyString())).thenReturn("$2a$10$hashedPassword");
            when(userRepo.save(any(UserJpaEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        }

        @Test
        @DisplayName("skips tenant creation but creates user")
        void onlyCreatesUser() {
            initializer.run(args);
            verify(tenantRepo, never()).save(any());
            verify(userRepo, times(1)).save(any(UserJpaEntity.class));
        }
    }
}
