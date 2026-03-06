package com.keevo.identity.auth.application.service;

import com.keevo.identity.auth.domain.model.PlanType;
import com.keevo.identity.auth.domain.model.Tenant;
import com.keevo.identity.auth.domain.model.TenantStatus;
import com.keevo.identity.auth.domain.port.out.TenantRepository;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import com.keevo.shared.infrastructure.persistence.TenantSchemaProvisioner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("TenantFactory")
class TenantFactoryTest {

    @Mock
    private TenantCodeGenerator codeGenerator;

    @Mock
    private TenantRepository tenantRepository;

    @Mock
    private TenantSchemaProvisioner schemaProvisioner;

    private TenantFactory tenantFactory;

    @BeforeEach
    void setUp() {
        tenantFactory = new TenantFactory(codeGenerator, tenantRepository, schemaProvisioner);
    }

    @Test
    @DisplayName("create() returns a persisted Tenant with correct code and schema")
    void create_returnsSavedTenant() {
        // arrange
        String code = "KV-ABC123";
        String schemaName = Tenant.schemaNameFromCode(code);
        Tenant savedTenant = new Tenant(UUID.randomUUID(), code, schemaName,
                TenantStatus.ACTIVE, PlanType.PREMIUM_TRIAL, Instant.now());

        when(codeGenerator.generate()).thenReturn(code);
        when(tenantRepository.save(any(Tenant.class))).thenReturn(savedTenant);

        // act
        Tenant result = tenantFactory.create();

        // assert
        assertThat(result.getCode()).isEqualTo(code);
        assertThat(result.getSchemaName()).isEqualTo(schemaName);
        verify(tenantRepository, times(1)).save(any(Tenant.class));
    }

    @Test
    @DisplayName("create() calls schemaProvisioner.provision() exactly once")
    void create_callsMigrateOnce() {
        // arrange
        String code = "KV-XY1234";
        Tenant savedTenant = new Tenant(UUID.randomUUID(), code,
                Tenant.schemaNameFromCode(code), TenantStatus.ACTIVE, PlanType.PREMIUM_TRIAL, Instant.now());

        when(codeGenerator.generate()).thenReturn(code);
        when(tenantRepository.save(any(Tenant.class))).thenReturn(savedTenant);

        // act
        tenantFactory.create();

        // assert
        verify(schemaProvisioner, times(1))
                .provision(anyString(), eq(Tenant.schemaNameFromCode(code)));
    }

    @Test
    @DisplayName("create() propagates DomainException when migration fails")
    void create_propagatesExceptionOnMigrationFailure() {
        // arrange
        String code = "KV-FAIL00";
        Tenant savedTenant = new Tenant(UUID.randomUUID(), code,
                Tenant.schemaNameFromCode(code), TenantStatus.ACTIVE, PlanType.PREMIUM_TRIAL, Instant.now());

        when(codeGenerator.generate()).thenReturn(code);
        when(tenantRepository.save(any(Tenant.class))).thenReturn(savedTenant);
        doThrow(new DomainException(ErrorCode.TENANT_PROVISION_FAILED))
                .when(schemaProvisioner).provision(anyString(), anyString());

        // act + assert
        DomainException ex = catchThrowableOfType(tenantFactory::create, DomainException.class);
        assertThat(ex.getDomainCode()).isEqualTo(ErrorCode.TENANT_PROVISION_FAILED.name());
    }

    @Test
    @DisplayName("create() calls dropSchemaIfExists() as compensating action on migration failure (AC4)")
    void create_dropsSchemaOnMigrationFailure() {
        // ── H2 RED: Verify the compensating action (AC4) is actually executed ──
        String code = "KV-DROP01";
        String expectedSchema = Tenant.schemaNameFromCode(code);
        Tenant savedTenant = new Tenant(UUID.randomUUID(), code,
                expectedSchema, TenantStatus.ACTIVE, PlanType.PREMIUM_TRIAL, Instant.now());

        when(codeGenerator.generate()).thenReturn(code);
        when(tenantRepository.save(any(Tenant.class))).thenReturn(savedTenant);
        doThrow(new DomainException(ErrorCode.TENANT_PROVISION_FAILED))
                .when(schemaProvisioner).provision(anyString(), anyString());

        catchThrowableOfType(tenantFactory::create, DomainException.class);

        // AC4 compensating action: orphaned schema must be cleaned up
        verify(schemaProvisioner, times(1)).dropSchemaIfExists(eq(expectedSchema));
    }

    @Test
    @DisplayName("1.4b — create() persists tenant with PREMIUM_TRIAL plan, not FREE (SPEC CHANGE 2026-03-06)")
    void create_persistsTenantAsPremiumTrial() {
        // ── RED first: verifies SPEC CHANGE 2026-03-06 ──
        // New tenants must start on a 6-month PREMIUM_TRIAL, NOT FREE.
        // The domain Tenant object carried through to tenantRepository.save() must use PREMIUM_TRIAL.
        // The SQL seed (TenantSchemaProvisioner.SEED_SUBSCRIPTION) inserts PREMIUM_TRIAL +
        //   expires_at = NOW() + INTERVAL '6 months'; verified by TenantMigrationIntegrationTest.
        String code = "KV-TRIAL1";
        ArgumentCaptor<Tenant> tenantCaptor = ArgumentCaptor.forClass(Tenant.class);
        when(codeGenerator.generate()).thenReturn(code);
        when(tenantRepository.save(tenantCaptor.capture())).thenAnswer(inv -> inv.getArgument(0));

        // act
        Tenant result = tenantFactory.create();

        // assert — domain object must carry PREMIUM_TRIAL, not FREE
        assertThat(result.getPlanType())
                .as("New tenant must start on PREMIUM_TRIAL, not FREE (SPEC CHANGE 2026-03-06)")
                .isEqualTo(PlanType.PREMIUM_TRIAL);
        assertThat(tenantCaptor.getValue().getPlanType())
                .as("Argument passed to tenantRepository.save() must be PREMIUM_TRIAL")
                .isEqualTo(PlanType.PREMIUM_TRIAL);
        assertThat(result.getPlanType()).isEqualTo(PlanType.PREMIUM_TRIAL);
    }
}
