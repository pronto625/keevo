package com.keevo.identity.auth.adapter.in.api;

import com.keevo.identity.api.UserQueryPort;
import com.keevo.identity.auth.domain.model.User;
import com.keevo.identity.auth.domain.model.UserMembershipInfo;
import com.keevo.identity.auth.domain.port.out.UserRepository;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Implements {@link UserQueryPort} over the {@link UserRepository} port (Story 12.2d). Users live in
 * the public schema and the owner lookup is keyed by the schema it is given, so no tenant-context
 * check is needed.
 */
@Component
class UserQueryAdapter implements UserQueryPort {

    private final UserRepository userRepository;

    UserQueryAdapter(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Override
    public Optional<String> findPhoneNumber(UUID userId) {
        return userRepository.findById(userId).map(User::getPhoneNumber);
    }

    @Override
    public List<UUID> findOwnerUserIds(String tenantSchema) {
        return userRepository.findOwnersByTenantSchemaName(tenantSchema).stream().map(User::getId).toList();
    }

    @Override
    public List<String> findMembershipSchemas(UUID userId) {
        return userRepository.findMembershipsWithTenantInfo(userId).stream()
                .map(UserMembershipInfo::schemaName)
                .toList();
    }
}
