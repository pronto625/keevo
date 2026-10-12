package com.keevo.shared.application.port.export;

import java.util.List;

/**
 * One keyset page of an export dataset (Story v1s-18-1).
 *
 * @param rows       row values, each in {@link ExportDatasetProvider#columns()} order
 * @param nextCursor opaque cursor of the next page, {@code null} when the dataset is exhausted
 */
public record ExportPage(List<List<Object>> rows, String nextCursor) {

    public ExportPage {
        rows = rows == null ? List.of() : rows;
    }
}
