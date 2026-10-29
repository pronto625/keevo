package com.keevo.architecture.fixture.application.service;

import com.keevo.shared.infrastructure.persistence.TenantContext;
import com.keevo.shared.infrastructure.persistence.TenantSchema;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Fixtures for {@code KeevoArchitectureRulesSelfCheckTest} (Story 12.6b) — never instantiated, not Spring beans.
 * The package ends in {@code application.service} so rule (c) applies to it.
 */
@SuppressWarnings("unused")
final class ArchitectureRuleFixtures {

    private ArchitectureRuleFixtures() {
    }

    /** (c) violation: servlet type in an application service. */
    static class WebLeakingService {
        String user(HttpServletRequest request) {
            return request.getRemoteUser();
        }
    }

    /** (f) a capability type. */
    interface FixtureCapability extends com.keevo.shared.module.Capability {
    }

    /** (f) violation: capability injected into a field. */
    static class CapabilityFieldConsumer {
        FixtureCapability capability;
    }

    /** (f) violation: capability injected through the constructor. */
    static class CapabilityConstructorConsumer {
        CapabilityConstructorConsumer(FixtureCapability capability) {
        }
    }

    /** (f) compliant: resolves the capability per tenant. */
    static class CapabilityRegistryUser {
        com.keevo.shared.module.CapabilityRegistry capabilities;

        boolean has(String tenant) {
            java.util.Optional<FixtureCapability> found = capabilities.find(FixtureCapability.class, tenant);
            return found.isPresent();
        }
    }

    /** (f) violation: extension contributions injected as a list. */
    static class ExtensionListConsumer {
        java.util.List<com.keevo.shared.module.Extension> extensions;
    }

    /** (f) violation: an extension contribution injected through the constructor. */
    static class ExtensionConstructorConsumer {
        ExtensionConstructorConsumer(com.keevo.shared.module.Extension extension) {
        }
    }

    /** (f) violation: a capability wrapped in a generic type. */
    static class CapabilityGeneric {
        java.util.Optional<com.keevo.crm.api.ClientLookupPort> lookup;
    }

    /** (f) violation: a capability as a @Bean method parameter. */
    static class CapabilityBeanMethod {
        @org.springframework.context.annotation.Bean
        Object consumer(FixtureCapability capability) {
            return capability;
        }
    }

    /** (f) violation: extension contributions through an @Autowired setter. */
    static class ExtensionAutowiredSetter {
        @org.springframework.beans.factory.annotation.Autowired
        void setExtensions(java.util.List<com.keevo.shared.module.Extension> extensions) {
        }
    }

    /** (f) compliant: a plain method taking an extension (not an injection point), and a @Bean returning one. */
    static class ExtensionPlainMethod {
        String label(com.keevo.shared.module.Extension extension) {
            return extension.label();
        }

        @org.springframework.context.annotation.Bean
        com.keevo.shared.module.Extension contribution() {
            return null;
        }
    }

    /** (f) compliant: lists the contributions per tenant. */
    static class ExtensionRegistryUser {
        com.keevo.shared.module.ExtensionRegistry extensions;

        int count(String tenant) {
            java.util.List<com.keevo.shared.module.Extension> listed = extensions.contributions("pos.x", tenant);
            return listed.size();
        }
    }

    record FixtureEvent(String id) {
    }

    static class FixtureListener {
        @ApplicationModuleListener
        void on(FixtureEvent event) {
        }
    }

    /** (e) violation: listened event published without a transaction. */
    static class NonTransactionalPublisher {
        ApplicationEventPublisher publisher;

        void publish() {
            publisher.publishEvent(new FixtureEvent("x"));
        }
    }

    /** (e) compliant: @Transactional method, and a private helper only called from it. */
    static class TransactionalPublisher {
        ApplicationEventPublisher publisher;

        @Transactional
        public void publish() {
            publisher.publishEvent(new FixtureEvent("x"));
            helper();
        }

        private void helper() {
            publisher.publishEvent(new FixtureEvent("y"));
        }
    }

    /** (e) compliant: diamond a(tx) → mid → left/right → sink; the second visit of mid/sink is neutral. */
    static class DiamondPublisher {
        ApplicationEventPublisher publisher;

        @Transactional
        public void a() {
            mid();
        }

        void mid() {
            left();
            right();
        }

        void left() {
            sink();
        }

        void right() {
            sink();
        }

        void sink() {
            publisher.publishEvent(new FixtureEvent("d"));
        }
    }

    /** (e) compliant: recursive helper only entered from a @Transactional method. */
    static class RecursivePublisher {
        ApplicationEventPublisher publisher;

        @Transactional
        public void entry() {
            recurse(3);
        }

        void recurse(int n) {
            publisher.publishEvent(new FixtureEvent("r" + n));
            if (n > 0) {
                recurse(n - 1);
            }
        }
    }

    /** (e) compliant: a module listener (meta-annotated @Transactional) publishing a listened event. */
    static class ListenerThatPublishes {
        ApplicationEventPublisher publisher;

        @ApplicationModuleListener
        void on(OtherEvent event) {
            publisher.publishEvent(new FixtureEvent("chained"));
        }
    }

    record OtherEvent(String id) {
    }

    record AfterCommitEvent(String id) {
    }

    static class AfterCommitListener {
        @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
        void on(AfterCommitEvent event) {
        }
    }

    /** (e) violation on one overload only: the key carries the parameter types. */
    static class OverloadedPublisher {
        ApplicationEventPublisher publisher;

        @Transactional
        public void publish(String id) {
            publisher.publishEvent(new AfterCommitEvent(id));
        }

        public void publish(int id) {
            publisher.publishEvent(new AfterCommitEvent(String.valueOf(id)));
        }
    }
}

// Rule (a) folds nested classes into their top-level class, so its fixtures are top-level.

/** (a) violation: tenant schema concatenated into SQL, never validated. */
class UnvalidatedSchemaQuery {
    JdbcTemplate jdbc;

    Long count() {
        String schema = TenantContext.getCurrentTenant();
        return jdbc.queryForObject("SELECT count(*) FROM \"" + schema + "\".sales", Long.class);
    }
}

/** (a) compliant. */
class ValidatedSchemaQuery {
    JdbcTemplate jdbc;

    Long count() {
        String schema = TenantSchema.validate(TenantContext.getCurrentTenant());
        return jdbc.queryForObject("SELECT count(*) FROM \"" + schema + "\".sales", Long.class);
    }
}
