package com.keevo.identity.auth.adapter.out.persistence.impl;

import com.keevo.identity.auth.domain.model.Role;
import com.keevo.identity.auth.domain.model.User;
import com.keevo.identity.auth.domain.port.out.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * v1s-14-17 — owner WhatsApp recipient rule (non-DB rows of the I/O matrix).
 */
@ExtendWith(MockitoExtension.class)
class OwnerRecipientResolverAdapterTest {

    private static final String TENANT = "kv_a";

    @Mock private UserRepository userRepository;

    private OwnerRecipientResolverAdapter resolver;

    @BeforeEach
    void setUp() {
        resolver = new OwnerRecipientResolverAdapter(userRepository);
    }

    private static User owner(String phone, boolean active) {
        return new User(UUID.randomUUID(), phone, "hash", Role.OWNER, active, Instant.now());
    }

    @Test
    void singleOwner_returnsCanonicalPhone() {
        when(userRepository.findOwnerByTenantSchemaName(TENANT)).thenReturn(Optional.of(owner("+237600000001", true)));

        assertThat(resolver.resolveOwnerWhatsAppPhone(TENANT)).contains("+237600000001");
    }

    @Test
    void untrimmedFormattedPhone_isNormalized() {
        when(userRepository.findOwnerByTenantSchemaName(TENANT))
                .thenReturn(Optional.of(owner("  237 600-000-001 ", true)));

        assertThat(resolver.resolveOwnerWhatsAppPhone(TENANT)).contains("+237600000001");
    }

    @Test
    void blankPhone_isEmpty() {
        when(userRepository.findOwnerByTenantSchemaName(TENANT)).thenReturn(Optional.of(owner("   ", true)));

        assertThat(resolver.resolveOwnerWhatsAppPhone(TENANT)).isEmpty();
    }

    @Test
    void nullPhone_isEmpty() {
        User owner = mock(User.class);
        when(owner.isActive()).thenReturn(true);
        when(owner.getPhoneNumber()).thenReturn(null);
        when(userRepository.findOwnerByTenantSchemaName(TENANT)).thenReturn(Optional.of(owner));

        assertThat(resolver.resolveOwnerWhatsAppPhone(TENANT)).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"abc", "+237abc", "++237600000001", "+", "237/600"})
    void garbagePhone_isEmpty(String phone) {
        when(userRepository.findOwnerByTenantSchemaName(TENANT)).thenReturn(Optional.of(owner(phone, true)));

        assertThat(resolver.resolveOwnerWhatsAppPhone(TENANT)).isEmpty();
    }

    @Test
    void noOwner_isEmpty() {
        when(userRepository.findOwnerByTenantSchemaName(TENANT)).thenReturn(Optional.empty());

        assertThat(resolver.resolveOwnerWhatsAppPhone(TENANT)).isEmpty();
    }

    @Test
    void inactiveOwnerUser_isEmpty_defence() {
        when(userRepository.findOwnerByTenantSchemaName(TENANT)).thenReturn(Optional.of(owner("+237600000001", false)));

        assertThat(resolver.resolveOwnerWhatsAppPhone(TENANT)).isEmpty();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t"})
    void nullOrBlankTenant_isEmpty_withoutLookup(String tenant) {
        assertThat(resolver.resolveOwnerWhatsAppPhone(tenant)).isEmpty();
        verifyNoInteractions(userRepository);
    }

    @Test
    void repositoryException_propagates() {
        when(userRepository.findOwnerByTenantSchemaName(TENANT)).thenThrow(new IllegalStateException("db down"));

        assertThatThrownBy(() -> resolver.resolveOwnerWhatsAppPhone(TENANT))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("db down");
    }
}
