package com.keevo.shared.infrastructure.pdf;

import com.openhtmltopdf.outputdevice.helper.BaseRendererBuilder.FontStyle;
import com.openhtmltopdf.pdfboxout.PdfBoxRenderer;
import com.openhtmltopdf.pdfboxout.PdfRendererBuilder;
import org.springframework.stereotype.Component;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.util.Locale;
import java.util.Map;

/**
 * Turns a versioned classpath template into a PDF (Story v1s-18-3): a standalone Thymeleaf
 * {@link TemplateEngine} (XML mode, no Spring MVC view resolver) fills
 * {@code pdf/templates/<name>.html} — values only through escaped {@code th:text} — then
 * OpenHTMLtoPDF lays the XHTML out (A4, running header, repeated table headers, page counter)
 * with the embedded Liberation Sans font (SIL OFL 1.1, subset), so accents render everywhere.
 *
 * <p>Story 14.2: moved from {@code reporting} to the core so every module renders its own documents (exports,
 * invoices) with the same engine, fonts and shared {@code layout.html} fragments.
 */
@Component
public class PdfDocumentFactory {

    public static final String FONT_FAMILY = "Keevo Sans";
    /** Story 20.7 — monospace family of the Bref ticket layouts (Liberation Mono, SIL OFL 1.1). */
    public static final String MONO_FONT_FAMILY = "Keevo Mono";
    /** Page height of the measuring pass of {@link #renderFitted} (long enough for any ticket). */
    static final int MEASURE_HEIGHT_MM = 3000;
    private static final String TEMPLATES = "pdf/templates/";
    private static final String FONTS = "pdf/fonts/";

    private final TemplateEngine engine;
    private final byte[] regularFont;
    private final byte[] boldFont;
    private final byte[] monoFont;
    private final byte[] monoBoldFont;

    public PdfDocumentFactory() {
        ClassLoaderTemplateResolver resolver = new ClassLoaderTemplateResolver(PdfDocumentFactory.class.getClassLoader());
        resolver.setPrefix(TEMPLATES);
        resolver.setSuffix(".html");
        resolver.setTemplateMode(TemplateMode.XML);
        resolver.setCharacterEncoding("UTF-8");
        resolver.setCacheable(true);
        this.engine = new TemplateEngine();
        this.engine.setTemplateResolver(resolver);
        this.regularFont = load(FONTS + "LiberationSans-Regular.ttf");
        this.boldFont = load(FONTS + "LiberationSans-Bold.ttf");
        this.monoFont = load(FONTS + "LiberationMono-Regular.ttf");
        this.monoBoldFont = load(FONTS + "LiberationMono-Bold.ttf");
    }

    /** XHTML of {@code template} filled with {@code variables}. */
    public String html(String template, Map<String, Object> variables) {
        Context context = new Context(Locale.FRANCE);
        context.setVariables(variables);
        return engine.process(template, context);
    }

    /** Render {@code template} as a PDF into {@code out} (left open). */
    public void render(String template, Map<String, Object> variables, OutputStream out) throws IOException {
        PdfRendererBuilder builder = builder(html(template, variables));
        builder.toStream(out);
        builder.run();
    }

    private PdfRendererBuilder builder(String xhtml) {
        PdfRendererBuilder builder = new PdfRendererBuilder();
        builder.useFastMode();
        builder.useFont(() -> new ByteArrayInputStream(regularFont), FONT_FAMILY, 400, FontStyle.NORMAL, true);
        builder.useFont(() -> new ByteArrayInputStream(boldFont), FONT_FAMILY, 700, FontStyle.NORMAL, true);
        builder.useFont(() -> new ByteArrayInputStream(monoFont), MONO_FONT_FAMILY, 400, FontStyle.NORMAL, true);
        builder.useFont(() -> new ByteArrayInputStream(monoBoldFont), MONO_FONT_FAMILY, 700, FontStyle.NORMAL, true);
        builder.withProducer("Keevo");
        builder.withHtmlContent(xhtml, null);
        return builder;
    }

    /**
     * Story 20.7 — a single page as tall as its content (thermal ticket rolls have no fixed page height): the template
     * reads its page height in millimetres from {@code heightVariable}; a first layout on a {@value #MEASURE_HEIGHT_MM}
     * mm page measures the content (no PDF written), then the document is rendered on a page of that height (at least
     * {@code minHeightMm}). Cost: two layouts of a small document.
     */
    public byte[] renderFitted(String template, Map<String, Object> variables, String heightVariable,
                               int minHeightMm) {
        Map<String, Object> measuring = new java.util.HashMap<>(variables);
        measuring.put(heightVariable, MEASURE_HEIGHT_MM);
        double contentMm;
        try (PdfBoxRenderer renderer = builder(html(template, measuring)).buildPdfRenderer()) {
            renderer.layout();
            float points = renderer.getRootBox().getHeight() / renderer.getDotsPerPoint();
            contentMm = points * 25.4 / 72.0;
        }
        Map<String, Object> fitted = new java.util.HashMap<>(variables);
        fitted.put(heightVariable, Math.max(minHeightMm, (int) Math.ceil(contentMm) + 1));
        return render(template, fitted);
    }

    /** Render {@code template} as a PDF in memory (small documents: an invoice). */
    public byte[] render(String template, Map<String, Object> variables) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(32 * 1024);
        try {
            render(template, variables, out);
        } catch (IOException e) {
            throw new UncheckedIOException("PDF rendering failed for template " + template, e);
        }
        return out.toByteArray();
    }

    private static byte[] load(String path) {
        try (InputStream in = PdfDocumentFactory.class.getClassLoader().getResourceAsStream(path)) {
            if (in == null) throw new IllegalStateException("Missing PDF font resource " + path);
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read PDF font " + path, e);
        }
    }
}
