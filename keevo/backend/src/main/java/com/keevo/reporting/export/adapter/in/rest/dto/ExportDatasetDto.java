package com.keevo.reporting.export.adapter.in.rest.dto;

import com.keevo.shared.application.port.export.ExportDatasetProvider;

import java.util.List;

/**
 * An exportable dataset, its column schema (Story v1s-18-1) and the filters it honours (Story
 * v1s-18-2): {@code storeFilter} / {@code periodFilter} say whether the store / period narrow it,
 * {@code criteria} lists the dataset-specific criteria keys it accepts (sorted). {@code pdfTemplate}
 * is the French title of the PDF document made from this dataset, {@code null} when it has none
 * (Story v1s-18-3: the app lists the PDF templates from this catalogue).
 */
public record ExportDatasetDto(String key, String label, List<Column> columns, boolean storeFilter,
                               boolean periodFilter, List<String> criteria, String pdfTemplate) {

    public record Column(String key, String label, String type) {}

    public static ExportDatasetDto from(ExportDatasetProvider p) {
        return from(p, null);
    }

    public static ExportDatasetDto from(ExportDatasetProvider p, String pdfTemplate) {
        return new ExportDatasetDto(p.key(), p.label(), p.columns().stream()
                .map(c -> new Column(c.key(), c.label(), c.type().name()))
                .toList(),
                p.supportsStoreFilter(), p.supportsPeriodFilter(), p.criteriaKeys().stream().sorted().toList(), pdfTemplate);
    }
}
