package com.keevo.identity.onboarding.application.service;

import com.keevo.identity.api.SaleFlow;
import com.keevo.identity.onboarding.domain.model.TenantPreferences;
import com.keevo.identity.onboarding.domain.port.in.UpdateSaleFlowUseCase;
import com.keevo.identity.onboarding.domain.port.out.TenantPreferencesRepository;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import com.keevo.shared.event.catalog.onboarding.SaleFlowChangedEvent;
import com.keevo.shared.infrastructure.persistence.TenantContext;
import com.keevo.shared.infrastructure.security.CurrentActor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Objects;

/**
 * UpdateSaleFlowService — Story 19.12: writes {@code tenant_preferences.default_sale_flow} (and nothing else) with one
 * conditional UPDATE, then audits the change before → after (« Caisse » / « Documents ») only when that statement
 * changed the row. Same value → no write, no audit. O(1): one row read, one conditional update.
 */
@Service
@Transactional
public class UpdateSaleFlowService implements UpdateSaleFlowUseCase {

    private final TenantPreferencesRepository preferencesRepository;
    private final ApplicationEventPublisher eventPublisher;

    public UpdateSaleFlowService(TenantPreferencesRepository preferencesRepository,
                                 ApplicationEventPublisher eventPublisher) {
        this.preferencesRepository = preferencesRepository;
        this.eventPublisher = eventPublisher;
    }

    @Override
    public TenantPreferences update(SaleFlow flow) {
        Objects.requireNonNull(flow, "flow");
        TenantPreferences existing = preferencesRepository.findByCurrentTenant()
                .orElseThrow(() -> new DomainException(ErrorCode.PREFERENCES_NOT_FOUND));
        if (existing.defaultSaleFlow() == flow) {
            return existing;
        }
        // one conditional UPDATE: audited only when this call changed the row (a concurrent PUT of the same value
        // changes nothing); with two flows the previous one is the other value
        if (preferencesRepository.updateDefaultSaleFlow(existing.id(), flow)) {
            SaleFlow before = flow == SaleFlow.POS ? SaleFlow.DOCUMENT : SaleFlow.POS;
            eventPublisher.publishEvent(new SaleFlowChangedEvent(existing.id(), before.label(), flow.label(),
                    CurrentActor.actorId(), TenantContext.getCurrentTenant(), Instant.now()));
        }
        return existing.withDefaultSaleFlow(flow);
    }
}
