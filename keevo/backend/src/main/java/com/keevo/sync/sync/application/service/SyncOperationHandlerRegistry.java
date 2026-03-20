package com.keevo.sync.sync.application.service;

import com.keevo.sync.sync.domain.port.in.SyncOperationHandler;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

@Component
public class SyncOperationHandlerRegistry {

    private final Map<String, SyncOperationHandler> handlers;

    public SyncOperationHandlerRegistry(List<SyncOperationHandler> handlers) {
        this.handlers = handlers.stream()
                .flatMap(h -> h.supportedTypes().stream().map(t -> Map.entry(t, h)))
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    public Optional<SyncOperationHandler> resolve(String operationType) {
        return Optional.ofNullable(handlers.get(operationType));
    }
}
