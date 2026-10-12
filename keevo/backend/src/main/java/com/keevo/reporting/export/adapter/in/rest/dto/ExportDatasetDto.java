package com.keevo.reporting.export.adapter.in.rest.dto;

import com.keevo.shared.application.port.export.ExportDatasetProvider;

import java.util.List;

/** An exportable dataset and its column schema (Story v1s-18-1). */
public record ExportDatasetDto(String key, String label, List<Column> columns) {

    public record Column(String key, String label, String type) {}

    public static ExportDatasetDto from(ExportDatasetProvider p) {
        return new ExportDatasetDto(p.key(), p.label(), p.columns().stream()
                .map(c -> new Column(c.key(), c.label(), c.type().name()))
                .toList());
    }
}
