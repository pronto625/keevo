package com.keevo.identity.auth.application.service;

import com.keevo.identity.auth.domain.port.out.TenantRepository;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("TenantCodeGenerator")
class TenantCodeGeneratorTest {

    @Mock
    private TenantRepository tenantRepository;

    private TenantCodeGenerator generator;

    @BeforeEach
    void setUp() {
        generator = new TenantCodeGenerator(tenantRepository);
    }

    @Test
    @DisplayName("generates code matching KV-[A-Z0-9]{6} format")
    void generate_returnsCorrectFormat() {
        when(tenantRepository.existsByCode(anyString())).thenReturn(false);

        String code = generator.generate();

        assertThat(code).matches("KV-[A-Z0-9]{6}");
    }

    @Test
    @DisplayName("generates 100 codes with no collisions (probabilistic uniqueness)")
    void generate_produces_unique_codes() {
        when(tenantRepository.existsByCode(anyString())).thenReturn(false);

        Set<String> codes = new HashSet<>();
        for (int i = 0; i < 100; i++) {
            codes.add(generator.generate());
        }

        assertThat(codes).hasSize(100);
    }

    @Test
    @DisplayName("retries when generated code already exists in repository")
    void generate_retries_on_collision() {
        // first call → already exists, second call → free
        when(tenantRepository.existsByCode(anyString()))
                .thenReturn(true)
                .thenReturn(false);

        String code = generator.generate();

        assertThat(code).matches("KV-[A-Z0-9]{6}");
        verify(tenantRepository, times(2)).existsByCode(anyString());
    }

    @Test
    @DisplayName("throws TENANT_PROVISION_FAILED after 10 failed attempts")
    void generate_throwsAfterMaxAttempts() {
        when(tenantRepository.existsByCode(anyString())).thenReturn(true);

        DomainException ex = catchThrowableOfType(
                generator::generate, DomainException.class);

        assertThat(ex).isNotNull();
        assertThat(ex.getDomainCode()).isEqualTo(ErrorCode.TENANT_PROVISION_FAILED.name());
    }
}
