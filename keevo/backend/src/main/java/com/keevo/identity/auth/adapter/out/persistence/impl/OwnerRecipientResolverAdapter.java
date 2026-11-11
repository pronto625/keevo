package com.keevo.identity.auth.adapter.out.persistence.impl;

import com.keevo.identity.auth.domain.model.User;
import com.keevo.identity.api.OwnerRecipientResolver;
import com.keevo.identity.auth.domain.port.out.UserRepository;
import com.keevo.shared.domain.PhoneNumbers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * OwnerRecipientResolverAdapter — v1s-14-17 implementation of {@link OwnerRecipientResolver}.
 *
 * <p>The only caller of {@link UserRepository#findOwnerByTenantSchemaName(String)} and
 * {@link UserRepository#findPrimaryOwnersByTenantSchemaNames} (enforced by
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
        return userRepository.findOwnerByTenantSchemaName(tenantSchemaName)
                .flatMap(owner -> recipientPhone(owner, tenantSchemaName));
    }

    /** Story 19.11b — one query for every tenant, the same rule per tenant. */
    @Override
    public Map<String, String> resolveOwnerWhatsAppPhones(Collection<String> tenantSchemaNames) {
        if (tenantSchemaNames == null || tenantSchemaNames.isEmpty()) {
            return Map.of();
        }
        List<String> schemas = tenantSchemaNames.stream().filter(s -> s != null && !s.isBlank()).distinct().toList();
        if (schemas.isEmpty()) {
            return Map.of();
        }
        Map<String, String> phones = new HashMap<>();
        userRepository.findPrimaryOwnersByTenantSchemaNames(schemas).forEach((schema, owner) ->
                recipientPhone(owner, schema).ifPresent(phone -> phones.put(schema, phone)));
        return phones;
    }

    private static Optional<String> recipientPhone(User owner, String tenantSchemaName) {
        if (owner == null || !owner.isActive()) {
            return Optional.empty();
        }
        String raw = owner.getPhoneNumber();
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
