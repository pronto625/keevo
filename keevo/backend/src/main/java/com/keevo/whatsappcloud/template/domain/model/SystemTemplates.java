package com.keevo.whatsappcloud.template.domain.model;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Story 21.5 — the templates Keevo creates on every connected account (Bref {@code system-templates.ts}), unchanged so
 * the accounts migrated from Bref keep their approved one: the invoice / quote template {@code bref_facture}
 * (UTILITY, PDF document header; {{1}} client, {{2}} « facture » or « devis », {{3}} reference, {{4}} shop).
 */
public final class SystemTemplates {

    public static final String INVOICE_NAME = "bref_facture";
    public static final String INVOICE_LANGUAGE = "fr";
    public static final String INVOICE_CATEGORY = "UTILITY";
    public static final String INVOICE_BODY =
            "Bonjour {{1}}, voici votre {{2}} {{3}} de {{4}}. Merci pour votre confiance !";
    public static final List<String> INVOICE_EXAMPLES = List.of("Aminata", "facture", "5F3A9C21", "Boutique Mode Akwa");
    public static final String INVOICE_FOOTER = "Envoyé avec Bref Point";

    private SystemTemplates() {
    }

    /** A one-page blank PDF: the example Meta requires to review a template with a document header. */
    public static byte[] samplePdf() {
        String[] objects = {
                "<< /Type /Catalog /Pages 2 0 R >>",
                "<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
                "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 595 842] >>"};
        StringBuilder pdf = new StringBuilder("%PDF-1.4\n");
        int[] offsets = new int[objects.length];
        for (int i = 0; i < objects.length; i++) {
            offsets[i] = pdf.length();
            pdf.append(i + 1).append(" 0 obj\n").append(objects[i]).append("\nendobj\n");
        }
        int xref = pdf.length();
        pdf.append("xref\n0 ").append(objects.length + 1).append("\n0000000000 65535 f \n");
        for (int offset : offsets) {
            pdf.append(String.format("%010d 00000 n \n", offset));
        }
        pdf.append("trailer\n<< /Size ").append(objects.length + 1).append(" /Root 1 0 R >>\nstartxref\n")
                .append(xref).append("\n%%EOF\n");
        return pdf.toString().getBytes(StandardCharsets.ISO_8859_1);
    }
}
