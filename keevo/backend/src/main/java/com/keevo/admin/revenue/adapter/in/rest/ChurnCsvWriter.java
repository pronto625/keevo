package com.keevo.admin.revenue.adapter.in.rest;

import com.keevo.admin.revenue.adapter.in.rest.dto.ChurnedTenantDto;
import com.keevo.admin.revenue.domain.model.ChurnReport;
import com.keevo.admin.revenue.domain.model.ChurnedTenant;

import java.nio.charset.StandardCharsets;
import java.time.format.DateTimeFormatter;

/**
 * Churn CSV (Story 9-3): UTF-8 with BOM (Excel), {@code ;} separator, French headers and values, no ids.
 * Decimals use a comma (French Excel).
 */
final class ChurnCsvWriter {

    static final String HEADER =
            "Entreprise;Fin du plan;Ancienneté payante (mois);CA des 30 jours avant la fin (XAF)";
    private static final String BOM = "﻿";
    private static final DateTimeFormatter FR_DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private ChurnCsvWriter() {}

    static byte[] write(ChurnReport report) {
        StringBuilder csv = new StringBuilder(BOM).append(HEADER).append("\r\n");
        for (ChurnedTenant row : report.tenants()) {
            String name = row.tenantName() == null || row.tenantName().isBlank()
                    ? ChurnedTenantDto.UNNAMED : row.tenantName();
            csv.append(escape(name)).append(';')
               .append(row.planEndDate().format(FR_DATE)).append(';')
               .append(row.tenureMonths() == null ? "" : String.valueOf(row.tenureMonths()).replace('.', ','))
               .append(';')
               .append(row.lastGmv30d())
               .append("\r\n");
        }
        return csv.toString().getBytes(StandardCharsets.UTF_8);
    }

    static String filename(ChurnReport report) {
        return "keevo-churn-" + report.month() + ".csv";
    }

    /** Quotes values containing the separator, quotes or line breaks; neutralises spreadsheet formulas. */
    static String escape(String value) {
        if (value == null) return "";
        String v = value;
        if (!v.isEmpty() && "=+-@\t\r".indexOf(v.charAt(0)) >= 0) {
            v = "'" + v;
        }
        if (v.contains(";") || v.contains("\"") || v.contains("\n") || v.contains("\r")) {
            return "\"" + v.replace("\"", "\"\"") + "\"";
        }
        return v;
    }
}
