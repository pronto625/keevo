package com.keevo.catalog.product.application.usecase;

import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;

/**
 * GenerateCsvTemplateUseCase — produces a UTF-8 CSV template with header + 3 example rows.
 *
 * <p>The column order matches the default {@link com.keevo.catalog.product.application.dto.CsvColumnMapping}
 * field names used by {@link ImportCsvProductsUseCase}.
 *
 * <p>Story 2.4 — AC1 (template download).
 */
@Service
public class GenerateCsvTemplateUseCase {

    private static final byte[] TEMPLATE_BYTES = buildTemplate();

    /** Returns the pre-built CSV template as a byte array (UTF-8, no BOM). */
    public byte[] execute() {
        return TEMPLATE_BYTES.clone();  // defensive copy — immutable template
    }

    // ── Template construction ─────────────────────────────────────────────────

    private static byte[] buildTemplate() {
        var baos = new ByteArrayOutputStream();
        try (var pw = new PrintWriter(new OutputStreamWriter(baos, StandardCharsets.UTF_8))) {
            pw.println("nom,prix_vente,prix_achat,cout_transport,categorie,sku,quantite_initiale,seuil_min");
            pw.println("Coca-Cola 33cl,150,80,0,Boissons,KEV-COCA01,100,10");
            pw.println("Pain de mie,200,120,15,Épicerie,,50,5");
            pw.println("Lait demi-écrémé 1L,350,200,0,Produits laitiers,,0,0");
        }
        return baos.toByteArray();
    }
}
