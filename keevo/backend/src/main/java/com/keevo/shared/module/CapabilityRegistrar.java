package com.keevo.shared.module;

/**
 * Programmatic registration of a module's {@link Capability capabilities} (Story 13.9), handed to
 * {@link KeevoModule#registerCapabilities} once at boot. Bound to the calling module: what is provided belongs to it.
 * Validated with the {@link ProvidesCapability} beans, all-or-nothing (the boot fails naming the module): the type
 * must be an interface extending {@code Capability} that the implementation implements, its simple name must be in
 * the module manifest's {@code provides}, and no other provider (annotated or programmatic) may provide it. One path
 * per capability: a capability is either annotated or registered here, never both.
 */
public interface CapabilityRegistrar {

    /** Provides {@code implementation} as the module's {@code type}; resolvable through {@link CapabilityRegistry#find}. */
    <T extends Capability> void provide(Class<T> type, T implementation);
}
