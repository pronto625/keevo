package com.keevo.invoicing;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 24.10 — the versioned proxy rule of the cutover ({@code keevo/ops/traefik/bref-legacy-links.yml}): the router
 * matches every Bref public link shape and nothing else of the old API, and the middleware sends it, with a 302, to
 * the same path on Keevo (served by {@code InvoiceLegacyLinkController}). The Traefik expressions are plain RE2
 * patterns that Java reads the same way.
 */
@DisplayName("Bref legacy links proxy rule (Story 24.10)")
class BrefLegacyProxyRuleTest {

    private static final Path RULE = Path.of("..", "ops", "traefik", "bref-legacy-links.yml");
    private static final String TOKEN = "1a2b3c4d-5e6f-4a7b-8c9d-0e1f2a3b4c5d";

    @SuppressWarnings("unchecked")
    private static Map<String, Object> http() throws Exception {
        try (Reader r = Files.newBufferedReader(RULE, StandardCharsets.UTF_8)) {
            return (Map<String, Object>) new Yaml().<Map<String, Object>>load(r).get("http");
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> entry(Map<String, Object> http, String kind, String name) {
        return (Map<String, Object>) ((Map<String, Object>) http.get(kind)).get(name);
    }

    @Test
    @DisplayName("router: the Bref API host and a UUID token only, with and without the api prefix")
    void routerMatchesOnlyPublicLinks() throws Exception {
        Map<String, Object> router = entry(http(), "routers", "bref-legacy-invoice-links");
        String rule = (String) router.get("rule");
        assertThat(rule).contains("Host(`api.brefpoint.app`)");
        Matcher m = Pattern.compile("PathRegexp\\(`(.+)`\\)").matcher(rule);
        assertThat(m.find()).isTrue();
        Pattern path = Pattern.compile(m.group(1));
        assertThat(path.matcher("/api/v1/invoices/public/" + TOKEN).matches()).isTrue();
        assertThat(path.matcher("/v1/invoices/public/" + TOKEN.toUpperCase()).matches()).isTrue();
        assertThat(path.matcher("/api/v1/invoices/public/count").matches()).isFalse();
        assertThat(path.matcher("/api/v1/invoices/public/" + TOKEN + "/x").matches()).isFalse();
        assertThat(path.matcher("/api/v1/invoices/" + TOKEN).matches()).isFalse();
        assertThat(path.matcher("/webhook/revenuecat").matches()).isFalse();
        assertThat(router.get("middlewares").toString()).contains("bref-legacy-invoice-redirect");
    }

    @Test
    @DisplayName("middleware: 302 (not permanent) to the same token path on Keevo")
    void middlewareRedirectsToKeevo() throws Exception {
        @SuppressWarnings("unchecked")
        Map<String, Object> redirect = (Map<String, Object>) entry(http(), "middlewares",
                "bref-legacy-invoice-redirect").get("redirectRegex");
        assertThat(redirect.get("permanent")).isEqualTo(false);
        assertThat(entry(http(), "routers", "bref-legacy-invoice-links").get("priority")).isEqualTo(10000);
        Pattern regex = Pattern.compile((String) redirect.get("regex"));
        String replacement = ((String) redirect.get("replacement")).replace("${2}", "$2");
        for (String from : new String[]{"https://api.brefpoint.app/api/v1/invoices/public/" + TOKEN,
                "https://api.brefpoint.app/v1/invoices/public/" + TOKEN,
                "https://api.brefpoint.app/api/v1/invoices/public/" + TOKEN + "?fbclid=abc&utm_source=whatsapp"}) {
            Matcher m = regex.matcher(from);
            assertThat(m.matches()).as(from).isTrue();
            assertThat(m.replaceFirst(replacement))
                    .isEqualTo("https://keevo.ad2s.cm/api/v1/invoices/public/" + TOKEN);
        }
    }
}
