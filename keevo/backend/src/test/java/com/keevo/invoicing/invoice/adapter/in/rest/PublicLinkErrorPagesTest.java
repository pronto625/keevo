package com.keevo.invoicing.invoice.adapter.in.rest;

import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import com.keevo.shared.infrastructure.web.ApiResponseWrapper;
import com.keevo.shared.infrastructure.web.GlobalExceptionHandler;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.bind.MissingServletRequestParameterException;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("PublicLinkErrorPages — French pages for browsers, JSON unchanged for API clients (Story 24.10)")
class PublicLinkErrorPagesTest {

    private static final String BROWSER = "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8";

    private final GlobalExceptionHandler global = new GlobalExceptionHandler();
    private final PublicLinkErrorPages pages = new PublicLinkErrorPages(global);

    private static MockHttpServletRequest request(String accept) {
        MockHttpServletRequest r = new MockHttpServletRequest("GET", "/api/v1/invoices/public/x");
        if (accept != null) {
            r.addHeader("Accept", accept);
        }
        return r;
    }

    @ParameterizedTest(name = "{0} → {1} « {2} »")
    @CsvSource({
            "INVOICE_LINK_INVALID, 404, Lien introuvable",
            "INVOICE_LINK_EXPIRED, 410, Lien expiré",
            "INVOICE_LINK_RATE_LIMITED, 429, Trop de tentatives",
            "SERVICE_UNAVAILABLE, 503, Service momentanément indisponible"})
    @DisplayName("a browser gets the French page with the JSON status, constant text only")
    void browserPage(ErrorCode code, int status, String title) {
        DomainException ex = new DomainException(code, "internal detail 7f3a schema kv_abc123");
        ResponseEntity<?> r = pages.domain(ex, request(BROWSER));

        assertThat(r.getStatusCode().value()).isEqualTo(status)
                .isEqualTo(global.handleDomainException(ex).getStatusCode().value());
        assertThat(r.getHeaders().getContentType()).isNotNull();
        assertThat(r.getHeaders().getContentType().isCompatibleWith(MediaType.TEXT_HTML)).isTrue();
        assertThat(r.getHeaders().getCacheControl()).isEqualTo("no-store");
        assertThat(r.getHeaders().getFirst("X-Robots-Tag")).contains("noindex");
        assertThat(r.getHeaders().getFirst("Referrer-Policy")).isEqualTo("no-referrer");
        assertThat(r.getHeaders().getVary()).containsExactly("Accept");
        String html = (String) r.getBody();
        assertThat(html).contains("<html lang=\"fr\">", "<h1>" + title + "</h1>")
                .doesNotContain("7f3a", "kv_abc123", code.name(), "internal", "domainCode", "%1$s");
    }

    @Test
    @DisplayName("an API client (JSON or no Accept) keeps the global JSON answer, body and status")
    void apiClientKeepsJson() {
        DomainException ex = new DomainException(ErrorCode.INVOICE_LINK_EXPIRED, "Legacy invoice link expired");
        for (String accept : new String[]{null, "application/json", "*/*"}) {
            ResponseEntity<?> r = pages.domain(ex, request(accept));
            assertThat(r.getStatusCode().value()).isEqualTo(410);
            assertThat(r.getBody()).isInstanceOf(ApiResponseWrapper.class);
            assertThat(((ApiResponseWrapper<?>) r.getBody()).domainCode()).isEqualTo("INVOICE_LINK_EXPIRED");
            assertThat(r.getHeaders().getVary()).containsExactly("Accept");
            assertThat(r.getHeaders().getCacheControl()).isEqualTo("no-store");
        }
    }

    @Test
    @DisplayName("shared link without its token: 400 kept; a browser sees « Lien introuvable », an API client JSON")
    void missingToken() {
        MissingServletRequestParameterException ex = new MissingServletRequestParameterException("token", "String");
        ResponseEntity<?> page = pages.missing(ex, request(BROWSER));
        assertThat(page.getStatusCode().value()).isEqualTo(400);
        assertThat((String) page.getBody()).contains("<h1>Lien introuvable</h1>").doesNotContain("token");

        ResponseEntity<?> json = pages.missing(ex, request("application/json"));
        assertThat(json.getStatusCode().value()).isEqualTo(400);
        assertThat(json.getBody()).isInstanceOf(ApiResponseWrapper.class);
    }

    @Test
    @DisplayName("Accept detection is case-insensitive and needs text/html")
    void wantsHtml() {
        assertThat(PublicLinkErrorPages.wantsHtml(request("TEXT/HTML"))).isTrue();
        assertThat(PublicLinkErrorPages.wantsHtml(request("application/pdf"))).isFalse();
        assertThat(PublicLinkErrorPages.wantsHtml(request(null))).isFalse();
    }
}
