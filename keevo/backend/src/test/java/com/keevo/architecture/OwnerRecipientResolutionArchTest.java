package com.keevo.architecture;

import com.keevo.identity.auth.adapter.out.persistence.impl.OwnerRecipientResolverAdapter;
import com.keevo.identity.auth.adapter.out.persistence.impl.UserRepositoryAdapter;
import com.keevo.identity.auth.domain.port.out.UserRepository;
import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.AccessTarget;
import com.tngtech.archunit.core.domain.JavaAccess;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.function.Function;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * v1s-14-17 — BLOCKING rule: the owner WhatsApp recipient is resolved in exactly one place.
 * Every owner-directed send goes through {@code OwnerRecipientResolver}; only its adapter may
 * access {@link UserRepository#findOwnerByTenantSchemaName(String)} — by call or method
 * reference, through any receiver type assignable to {@link UserRepository} (port or adapter).
 */
class OwnerRecipientResolutionArchTest {

    private static final String METHOD = "findOwnerByTenantSchemaName";

    /** Any access (call, method reference) to findOwnerByTenantSchemaName on a UserRepository subtype. */
    private static final DescribedPredicate<JavaAccess<?>> FIND_OWNER_ACCESS =
            new DescribedPredicate<>("access " + METHOD + " on a UserRepository") {
                @Override
                public boolean test(JavaAccess<?> access) {
                    AccessTarget target = access.getTarget();
                    return METHOD.equals(target.getName())
                            && target.getOwner().isAssignableTo(UserRepository.class);
                }
            };

    private static ArchRule rule() {
        return noClasses()
                .that().doNotHaveFullyQualifiedName(OwnerRecipientResolverAdapter.class.getName())
                .should().accessTargetWhere(FIND_OWNER_ACCESS)
                .because("owner WhatsApp recipients must go through OwnerRecipientResolver (v1s-14-17)");
    }

    @Test
    void onlyOwnerRecipientResolverAdapterAccessesFindOwnerByTenantSchemaName() {
        JavaClasses mainClasses = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.keevo");

        rule().check(mainClasses);
    }

    /** Guards the rule itself: method references and adapter-typed receivers are caught too. */
    @Test
    void ruleCatchesMethodReference() {
        JavaClasses fixture = new ClassFileImporter().importClasses(MethodReferenceFixture.class);

        assertThatThrownBy(() -> rule().check(fixture)).isInstanceOf(AssertionError.class);
    }

    @Test
    void ruleCatchesAdapterTypedReceiver() {
        JavaClasses fixture = new ClassFileImporter().importClasses(AdapterReceiverFixture.class);

        assertThatThrownBy(() -> rule().check(fixture)).isInstanceOf(AssertionError.class);
    }

    @SuppressWarnings("unused")
    static class MethodReferenceFixture {
        Function<String, Optional<?>> lookup(UserRepository repository) {
            return repository::findOwnerByTenantSchemaName;
        }
    }

    @SuppressWarnings("unused")
    static class AdapterReceiverFixture {
        Object lookup(UserRepositoryAdapter adapter) {
            return adapter.findOwnerByTenantSchemaName("kv_a");
        }
    }
}
