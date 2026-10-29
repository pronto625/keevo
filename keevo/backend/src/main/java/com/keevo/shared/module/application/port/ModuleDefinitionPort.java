package com.keevo.shared.module.application.port;

import com.keevo.shared.module.LoadedModuleManifest;

import java.util.Map;

/**
 * Write port of the global module catalogue ({@code public.module_definitions}, spec-socle §2.1), used by the
 * boot seed. Implemented by {@code shared.module.adapter.out.persistence.JdbcModuleDefinitionStore}.
 */
public interface ModuleDefinitionPort {

    /** Outcome of an {@link #upsert}. */
    enum UpsertOutcome { INSERTED, UPDATED, UNCHANGED }

    /**
     * Inserts the module if absent. Otherwise updates version, display name, category, requires, enhances,
     * is_free and the manifest cache, but only when one of them differs and the incoming version is not older
     * than the stored one (an older build never downgrades the catalogue). {@code installed_at} is never changed.
     * An older incoming version, or identical content, returns {@link UpsertOutcome#UNCHANGED}.
     */
    UpsertOutcome upsert(LoadedModuleManifest manifest);

    /** Stored version of every catalogue row, by module id, including modules no longer on the classpath. */
    Map<String, String> findVersions();
}
