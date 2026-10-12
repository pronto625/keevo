package com.keevo.reporting.export.application.service;

import com.keevo.reporting.export.domain.model.ExportFormat;
import com.keevo.reporting.export.domain.port.out.ExportRenderer;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Registry of the {@link ExportRenderer} strategies by format (Story v1s-18-1). Two renderers for
 * the same format fail the application start.
 */
@Component
public class ExportRendererRegistry {

    private final Map<ExportFormat, ExportRenderer> byFormat = new EnumMap<>(ExportFormat.class);

    public ExportRendererRegistry(List<ExportRenderer> renderers) {
        for (ExportRenderer r : renderers) {
            ExportRenderer previous = byFormat.putIfAbsent(r.format(), r);
            if (previous != null) {
                throw new IllegalStateException("Duplicate export renderer for " + r.format() + ": "
                        + previous.getClass().getName() + " and " + r.getClass().getName());
            }
        }
    }

    public Optional<ExportRenderer> find(ExportFormat format) {
        return Optional.ofNullable(byFormat.get(format));
    }

    /** @throws DomainException EXPORT_FORMAT_UNSUPPORTED (400) */
    public ExportRenderer require(ExportFormat format) {
        return find(format).orElseThrow(() -> new DomainException(ErrorCode.EXPORT_FORMAT_UNSUPPORTED,
                "No renderer for format", Map.of("format", String.valueOf(format))));
    }
}
