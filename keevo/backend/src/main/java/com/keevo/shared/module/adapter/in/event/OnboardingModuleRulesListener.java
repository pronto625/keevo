package com.keevo.shared.module.adapter.in.event;

import com.keevo.shared.event.catalog.onboarding.OnboardingCompletedEvent;
import com.keevo.shared.module.ModuleActivationRulesPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Story 19.13 — once onboarding committed (the sale flow chosen at sign-up is saved), applies the initial module
 * activation rules of the tenant's sale flow and sector ({@link ModuleActivationRulesPort}). A failure is logged and
 * rethrown: the onboarding itself is already committed, and the event publication stays incomplete so the rules are
 * applied again when outstanding events are republished on restart (idempotent insert). The rules run in their own transaction ({@code REQUIRES_NEW}): after commit, the onboarding's
 * connection is still bound to the thread and a write joining it would never be committed.
 */
@Component
public class OnboardingModuleRulesListener {

    private static final Logger log = LoggerFactory.getLogger(OnboardingModuleRulesListener.class);

    private final ModuleActivationRulesPort rules;
    private final TransactionTemplate requiresNew;

    public OnboardingModuleRulesListener(ModuleActivationRulesPort rules,
                                         PlatformTransactionManager transactionManager) {
        this.rules = rules;
        this.requiresNew = new TransactionTemplate(transactionManager);
        this.requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void on(OnboardingCompletedEvent event) {
        try {
            requiresNew.executeWithoutResult(status -> rules.applyInitialRules(event.tenantId()));
        } catch (RuntimeException e) {
            // rethrown: the event publication stays incomplete and is republished on restart (the insert is
            // idempotent: ON CONFLICT DO NOTHING keeps any owner choice made meanwhile)
            log.error("Module activation rules NOT applied after onboarding ({})", e.getClass().getSimpleName(), e);
            throw e;
        }
    }
}
