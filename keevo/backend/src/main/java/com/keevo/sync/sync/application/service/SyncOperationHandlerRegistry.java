package com.keevo.sync.sync.application.service;

import com.keevo.shared.module.ModuleManifest;
import com.keevo.shared.module.ModuleRegistry;
import com.keevo.sync.sync.domain.port.in.SyncOperationHandler;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Every {@link SyncOperationHandler} bean of the context, indexed by operation type (discovered by Spring — no
 * hard-coded list).
 *
 * <p>Story 12.7 — checked at construction, so a bad handler fails the boot with a message naming it: each handler
 * declares a {@linkplain SyncOperationHandler#moduleId() module id} that is installed (a manifest of the catalogue),
 * and each operation type is handled by exactly one handler.
 */
@Component
public class SyncOperationHandlerRegistry {

    private final Map<String, SyncOperationHandler> handlers;

    @Autowired
    public SyncOperationHandlerRegistry(List<SyncOperationHandler> handlers, ModuleRegistry moduleRegistry) {
        this(handlers, moduleRegistry.installed().stream().map(ModuleManifest::id).collect(Collectors.toSet()));
    }

    /** Registry checked against an explicit set of installed module ids (tests). */
    public SyncOperationHandlerRegistry(List<SyncOperationHandler> handlers, Collection<String> installedModuleIds) {
        Set<String> installed = Set.copyOf(installedModuleIds);
        Map<String, SyncOperationHandler> index = new HashMap<>();
        for (SyncOperationHandler handler : handlers) {
            String name = handler.getClass().getName();
            String moduleId = handler.moduleId();
            if (moduleId == null || !installed.contains(moduleId)) {
                throw new IllegalStateException("Sync handler " + name + " declares module '" + moduleId
                        + "', which is not an installed module (installed: " + installed.stream().sorted().toList()
                        + ")");
            }
            Set<String> types = handler.supportedTypes();
            if (types == null || types.isEmpty()) {
                throw new IllegalStateException("Sync handler " + name + " supports no operation type");
            }
            for (String type : types) {
                SyncOperationHandler previous = index.putIfAbsent(type, handler);
                if (previous != null) {
                    throw new IllegalStateException("Operation type " + type + " is handled by both "
                            + previous.getClass().getName() + " and " + name);
                }
            }
        }
        this.handlers = Map.copyOf(index);
    }

    public Optional<SyncOperationHandler> resolve(String operationType) {
        return operationType == null ? Optional.empty() : Optional.ofNullable(handlers.get(operationType));
    }
}
