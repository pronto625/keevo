package com.keevo.shared.module;

/**
 * Marker of an optional synchronous capability offered by one module to the others (Story 13.6, spec-socle §6,
 * MOD-FR9).
 *
 * <p>A capability type is an interface extending this one, living in the provider's {@code api} named interface
 * (e.g. {@code crm.api.ClientLookupPort}); its implementation is a bean of the provider module annotated
 * {@link ProvidesCapability}, and the capability's simple name is listed in the provider's manifest
 * {@code provides}. Consumers never inject the implementation: they resolve it per tenant through
 * {@link CapabilityRegistry#find}, and degrade when it is absent.
 */
public interface Capability {
}
