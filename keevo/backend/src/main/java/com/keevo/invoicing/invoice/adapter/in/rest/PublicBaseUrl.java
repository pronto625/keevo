package com.keevo.invoicing.invoice.adapter.in.rest;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.util.ForwardedHeaderUtils;
import org.springframework.web.util.UriComponents;

/**
 * Absolute base URL of the links sent outside the app (Story 14.2): {@code keevo.public-base-url} when set (prod:
 * {@code https://keevo.ad2s.cm}), else derived from the current request — {@code Forwarded} /
 * {@code X-Forwarded-*} headers honoured (the backend runs behind a reverse proxy) — plus the context path.
 */
@Component
public class PublicBaseUrl {

    private final String configured;

    public PublicBaseUrl(@Value("${keevo.public-base-url:}") String configured) {
        this.configured = configured == null ? "" : configured.strip();
    }

    public String resolve(HttpServletRequest request) {
        if (!configured.isEmpty()) {
            return configured.endsWith("/") ? configured.substring(0, configured.length() - 1) : configured;
        }
        ServletServerHttpRequest server = new ServletServerHttpRequest(request);
        HttpHeaders headers = server.getHeaders();
        UriComponents uri = ForwardedHeaderUtils.adaptFromForwardedHeaders(server.getURI(), headers).build();
        StringBuilder base = new StringBuilder().append(uri.getScheme()).append("://").append(uri.getHost());
        int port = uri.getPort();
        boolean defaultPort = port == -1 || ("http".equals(uri.getScheme()) && port == 80)
                || ("https".equals(uri.getScheme()) && port == 443);
        if (!defaultPort) {
            base.append(':').append(port);
        }
        String context = request.getContextPath();
        if (context != null && !context.isEmpty() && !"/".equals(context)) {
            base.append(context);
        }
        return base.toString();
    }
}
