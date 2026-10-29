package com.keevo.identity.auth.adapter.out.persistence.impl;

import com.keevo.identity.auth.domain.model.User;
import com.keevo.identity.api.OwnerRecipientResolver;
import com.keevo.identity.auth.domain.port.out.UserRepository;
import com.keevo.shared.domain.PhoneNumbers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.regex.Pattern;

/**
 * OwnerRecipientResolverAdapter — v1s-14-17 implementation of {@link OwnerRecipientResolver}.
 *
 * <p>The only caller of {@link UserRepository#findOwnerByTenantSchemaName(String)} (enforced by
 * {@code OwnerRecipientResolutionArchTest}). Never logs the phone value.
 */
@Component
public class OwnerRecipientResolverAdapter implements OwnerRecipientResolver {

    private static final Logger log = LoggerFactory.getLogger(OwnerRecipientResolverAdapter.class);
    private static final Pattern CANONICAL_PHONE = Pattern.compile("^\\+\\d+$");

    private final UserRepository userRepository;

    public OwnerRecipientResolverAdapter(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Override
    public Optional<String> resolveOwnerWhatsAppPhone(String tenantSchemaName) {
        if (tenantSchemaName == null || tenantSchemaName.isBlank()) {
            return Optional.empty();
        }
        Optional<User> owner = userRepository.findOwnerByTenantSchemaName(tenantSchemaName)
                .filter(User::isActive);
        if (owner.isEmpty()) {
            return Optional.empty();
        }
        String raw = owner.get().getPhoneNumber();
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        String normalized = PhoneNumbers.normalize(raw);
        if (normalized == null || !CANONICAL_PHONE.matcher(normalized).matches()) {
            log.warn("[OwnerRecipient] Owner phone rejected (not +<digits>) for tenant={}", tenantSchemaName);
            return Optional.empty();
        }
        return Optional.of(normalized);
    }
}
