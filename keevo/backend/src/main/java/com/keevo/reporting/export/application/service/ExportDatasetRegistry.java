package com.keevo.reporting.export.application.service;

import com.keevo.shared.application.port.export.ExportDatasetProvider;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Registry of the {@link ExportDatasetProvider} beans contributed by the domains, by key
 * (Story v1s-18-1). A key not matching {@code [a-z][a-z0-9_]{0,39}}, or two providers with the
 * same key, fail the application start.
 */
@Component
public class ExportDatasetRegistry {

    /** Keys reach file names, zip entries, Content-Disposition and the {@code datasets} column. */
    static final Pattern KEY_PATTERN = Pattern.compile("^[a-z][a-z0-9_]{0,39}$");

    private final Map<String, ExportDatasetProvider> byKey = new LinkedHashMap<>();

    public ExportDatasetRegistry(List<ExportDatasetProvider> providers) {
        providers.stream()
                .sorted(Comparator.comparing(ExportDatasetProvider::key))
                .forEach(p -> {
                    if (p.key() == null || !KEY_PATTERN.matcher(p.key()).matches()) {
                        throw new IllegalStateException("Invalid export dataset key '" + p.key() + "' in "
                                + p.getClass().getName() + ": must match " + KEY_PATTERN.pattern());
                    }
                    ExportDatasetProvider previous = byKey.putIfAbsent(p.key(), p);
                    if (previous != null) {
                        throw new IllegalStateException("Duplicate export dataset key '" + p.key() + "': "
                                + previous.getClass().getName() + " and " + p.getClass().getName());
                    }
                });
    }

    public Optional<ExportDatasetProvider> find(String key) {
        return Optional.ofNullable(byKey.get(key));
    }

    /** @throws DomainException EXPORT_DATASET_UNKNOWN (400) */
    public ExportDatasetProvider require(String key) {
        return find(key).orElseThrow(() -> new DomainException(ErrorCode.EXPORT_DATASET_UNKNOWN,
                "Unknown export dataset", Map.of("dataset", String.valueOf(key))));
    }

    /** Every dataset, sorted by key. */
    public List<ExportDatasetProvider> all() {
        return List.copyOf(byKey.values());
    }
}
