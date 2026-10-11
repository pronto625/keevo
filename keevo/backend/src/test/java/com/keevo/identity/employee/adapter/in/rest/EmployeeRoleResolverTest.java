package com.keevo.identity.employee.adapter.in.rest;

import com.keevo.identity.auth.domain.model.User;
import com.keevo.identity.auth.domain.port.out.TenantRepository;
import com.keevo.identity.auth.domain.port.out.UserMembershipRepository;
import com.keevo.identity.auth.domain.port.out.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Story 14.11 (AC6) — phone enrichment of {@code EmployeeResponseDto}.
 */
@ExtendWith(MockitoExtension.class)
class EmployeeRoleResolverTest {

    @Mock UserMembershipRepository membershipRepository;
    @Mock TenantRepository tenantRepository;
    @Mock UserRepository userRepository;

    @InjectMocks EmployeeRoleResolver resolver;

    @Test
    @DisplayName("resolvePhone returns the public.users phone number")
    void resolvePhone_returnsUserPhone() {
        UUID userId = UUID.randomUUID();
        User user = mock(User.class);
        when(user.getPhoneNumber()).thenReturn("+237690000001");
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));

        assertThat(resolver.resolvePhone(userId)).isEqualTo("+237690000001");
    }

    @Test
    @DisplayName("resolvePhone returns null for an unknown user")
    void resolvePhone_returnsNull_whenUserUnknown() {
        UUID userId = UUID.randomUUID();
        when(userRepository.findById(userId)).thenReturn(Optional.empty());

        assertThat(resolver.resolvePhone(userId)).isNull();
    }
}
