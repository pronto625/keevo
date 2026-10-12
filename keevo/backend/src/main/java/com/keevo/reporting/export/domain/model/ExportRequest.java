package com.keevo.reporting.export.domain.model;

import com.keevo.shared.application.port.export.ExportFilter;

import java.util.List;

/**
 * An owner's export request (Story v1s-18-1).
 *
 * @param datasets dataset keys, in output order (non-empty, no duplicates)
 * @param format   requested format
 * @param filter   store / period filters
 */
public record ExportRequest(List<String> datasets, ExportFormat format, ExportFilter filter) {

    public ExportRequest {
        datasets = datasets == null ? List.of() : List.copyOf(datasets);
        filter = filter == null ? ExportFilter.none() : filter;
    }
}
