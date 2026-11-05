package com.keevo.shared.module.adapter.in.event;

import com.keevo.shared.event.catalog.onboarding.OnboardingCompletedEvent;
import com.keevo.shared.event.catalog.onboarding.SectorKind;
import com.keevo.shared.module.ModuleActivationRulesPort;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Story 19.13 — the onboarding rules listener: own transaction, failures propagate (publication stays incomplete). */
@DisplayName("19.13 — OnboardingModuleRulesListener")
class OnboardingModuleRulesListenerTest {

    private static final String SCHEMA = "kv_onb013";

    private final ModuleActivationRulesPort rules = mock(ModuleActivationRulesPort.class);
    private final PlatformTransactionManager txManager = mock(PlatformTransactionManager.class);
    private final OnboardingModuleRulesListener listener = new OnboardingModuleRulesListener(rules, txManager);

    private static OnboardingCompletedEvent event() {
        return new OnboardingCompletedEvent(SCHEMA, SectorKind.CLOTHING, "Boutique", 6, UUID.randomUUID(),
                Instant.now());
    }

    @Test
    @DisplayName("success: the rules are applied in a REQUIRES_NEW transaction, committed")
    void appliesInRequiresNew() {
        SimpleTransactionStatus status = new SimpleTransactionStatus();
        when(txManager.getTransaction(any())).thenReturn(status);

        listener.on(event());

        ArgumentCaptor<TransactionDefinition> definition = ArgumentCaptor.forClass(TransactionDefinition.class);
        verify(txManager).getTransaction(definition.capture());
        assertThat(definition.getValue().getPropagationBehavior())
                .isEqualTo(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        verify(rules).applyInitialRules(SCHEMA);
        verify(txManager).commit(status);
    }

    @Test
    @DisplayName("failure: rolled back and rethrown (the event publication stays incomplete, republished on restart)")
    void failurePropagates() {
        SimpleTransactionStatus status = new SimpleTransactionStatus();
        when(txManager.getTransaction(any())).thenReturn(status);
        when(rules.applyInitialRules(SCHEMA)).thenThrow(new IllegalStateException("db down"));

        assertThatThrownBy(() -> listener.on(event())).isInstanceOf(IllegalStateException.class);
        verify(txManager).rollback(status);
        verify(txManager, never()).commit(status);
    }
}
