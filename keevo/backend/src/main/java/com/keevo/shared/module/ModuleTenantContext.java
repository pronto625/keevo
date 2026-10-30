package com.keevo.shared.module;

import java.util.Objects;

/**
 * The tenant a {@link KeevoModule} lifecycle hook runs for (Story 13.9, spec-socle §4.2). No HTTP / Spring-web type:
 * the actor is passed explicitly.
 *
 * @param tenantSchema the tenant schema ({@code kv_xxxxxx}) — for queries only, never shown nor logged
 * @param tenantCode   the tenant code ({@code KV-XXXXXX}) — what logs name
 * @param actor        who triggered the change
 */
public record ModuleTenantContext(String tenantSchema, String tenantCode, ActorRef actor) {

    public ModuleTenantContext {
        Objects.requireNonNull(tenantSchema, "tenantSchema");
        Objects.requireNonNull(tenantCode, "tenantCode");
        Objects.requireNonNull(actor, "actor");
    }
}
