package com.keevo.identity.auth.adapter.in.api;

import com.keevo.identity.api.TenantQueryPort;
import com.keevo.identity.api.TenantView;
import com.keevo.identity.auth.domain.port.out.TenantRepository;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/** Implements {@link TenantQueryPort} over the {@link TenantRepository} port (Story 12.2d). */
@Component
class TenantQueryAdapter implements TenantQueryPort {

    private final TenantRepository tenantRepository;

    TenantQueryAdapter(TenantRepository tenantRepository) {
        this.tenantRepository = tenantRepository;
    }

    @Override
    public List<TenantView> findAll() {
        return tenantRepository.findAll().stream().map(TenantViews::of).toList();
    }

    @Override
    public Optional<TenantView> findBySchemaName(String schemaName) {
        return tenantRepository.findBySchemaName(schemaName).map(TenantViews::of);
    }
}
