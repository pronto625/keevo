package com.keevo.invoicing.invoice.adapter.in.rest;

import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.infrastructure.web.GlobalExceptionHandler;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Story 24.10 — the failures of the two public invoice links opened in a browser (an old Bref link redirected to
 * Keevo, or the signed Keevo link it leads to): a request that accepts {@code text/html} gets a short static French page
 * with the same HTTP status (404 lien introuvable, 410 lien expiré, 429 trop de tentatives, otherwise service
 * indisponible); any other client keeps the JSON body of {@link GlobalExceptionHandler} unchanged (same status table,
 * same metrics — V1 compatibility). The pages are constant text: no token, document, company or error code is ever
 * printed. Scoped to the two controllers and ordered before the global handler.
 */
@RestControllerAdvice(assignableTypes = {InvoiceLegacyLinkController.class, InvoiceSharedLinkController.class})
@Order(Ordered.HIGHEST_PRECEDENCE)
public class PublicLinkErrorPages {

    /** One page: title and the two lines under it (French, constant). */
    record Page(String title, String message, String hint) {
    }

    static final Page NOT_FOUND = new Page("Lien introuvable",
            "Ce lien ne correspond à aucun document disponible. Il a peut-être été remplacé par un lien plus récent.",
            "Demandez à votre commerçant de vous renvoyer la facture.");
    static final Page EXPIRED = new Page("Lien expiré",
            "Ce lien de facture n'est plus valable.",
            "Demandez à votre commerçant de vous renvoyer la facture.");
    static final Page TOO_MANY = new Page("Trop de tentatives",
            "Ce lien a été ouvert trop souvent en peu de temps.",
            "Réessayez dans quelques minutes.");
    static final Page UNAVAILABLE = new Page("Service momentanément indisponible",
            "Le document ne peut pas être affiché pour le moment.",
            "Réessayez dans quelques instants.");

    /** The four pages, rendered once. */
    private static final java.util.Map<Page, String> HTML = java.util.Map.of(NOT_FOUND, html(NOT_FOUND),
            EXPIRED, html(EXPIRED), TOO_MANY, html(TOO_MANY), UNAVAILABLE, html(UNAVAILABLE));

    private final GlobalExceptionHandler json;

    public PublicLinkErrorPages(GlobalExceptionHandler json) {
        this.json = json;
    }

    @ExceptionHandler(DomainException.class)
    public ResponseEntity<?> domain(DomainException ex, HttpServletRequest request) {
        ResponseEntity<?> body = json.handleDomainException(ex);
        return wantsHtml(request) ? page(body.getStatusCode()) : varyOnAccept(body);
    }

    /** {@code /api/v1/invoices/shared} without its token: 400 kept, the browser sees « lien introuvable ». */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<?> missing(MissingServletRequestParameterException ex, HttpServletRequest request) {
        ResponseEntity<?> body = json.handleMissingParam(ex);
        return wantsHtml(request) ? page(body.getStatusCode(), NOT_FOUND) : varyOnAccept(body);
    }

    /** The same URL answers HTML or JSON by {@code Accept}: caches must key on it and keep nothing. */
    private static ResponseEntity<?> varyOnAccept(ResponseEntity<?> body) {
        return ResponseEntity.status(body.getStatusCode())
                .headers(body.getHeaders())
                .cacheControl(CacheControl.noStore())
                .header(HttpHeaders.VARY, HttpHeaders.ACCEPT)
                .body(body.getBody());
    }

    /** A browser: its {@code Accept} lists {@code text/html} (API clients send JSON or nothing). */
    static boolean wantsHtml(HttpServletRequest request) {
        String accept = request.getHeader(HttpHeaders.ACCEPT);
        return accept != null && accept.toLowerCase(java.util.Locale.ROOT).contains("text/html");
    }

    static Page pageFor(HttpStatusCode status) {
        return switch (status.value()) {
            case 404 -> NOT_FOUND;
            case 410 -> EXPIRED;
            case 429 -> TOO_MANY;
            default -> UNAVAILABLE;
        };
    }

    private static ResponseEntity<String> page(HttpStatusCode status) {
        return page(status, pageFor(status));
    }

    private static ResponseEntity<String> page(HttpStatusCode status, Page page) {
        return ResponseEntity.status(status)
                .contentType(new MediaType(MediaType.TEXT_HTML, java.nio.charset.StandardCharsets.UTF_8))
                .cacheControl(CacheControl.noStore())
                .header(HttpHeaders.VARY, HttpHeaders.ACCEPT)
                .header("Referrer-Policy", "no-referrer")
                .header("X-Robots-Tag", "noindex, nofollow")
                .header("Content-Security-Policy", "default-src 'none'; style-src 'unsafe-inline'")
                .body(HTML.get(page));
    }

    static String html(Page page) {
        return """
                <!doctype html>
                <html lang="fr">
                <head>
                <meta charset="utf-8">
                <meta name="viewport" content="width=device-width, initial-scale=1">
                <meta name="robots" content="noindex, nofollow">
                <title>%1$s</title>
                <style>
                body{margin:0;font-family:-apple-system,"Segoe UI",Roboto,Arial,sans-serif;background:#f6f7f9;color:#1f2933}
                main{max-width:28rem;margin:12vh auto 0;padding:2rem 1.5rem;background:#fff;border-radius:12px;
                box-shadow:0 1px 3px rgba(0,0,0,.08);text-align:center}
                h1{font-size:1.35rem;margin:0 0 1rem}
                p{line-height:1.5;margin:.5rem 0}
                .hint{color:#52606d}
                </style>
                </head>
                <body>
                <main>
                <h1>%1$s</h1>
                <p>%2$s</p>
                <p class="hint">%3$s</p>
                </main>
                </body>
                </html>
                """.formatted(page.title(), page.message(), page.hint());
    }
}
