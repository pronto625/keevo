package com.keevo.payments.appstore.adapter.out.revenuecat;

import com.fasterxml.jackson.databind.JsonNode;
import com.keevo.payments.appstore.domain.port.out.RevenueCatClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.util.UriUtils;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Story 21.2 — RevenueCat REST API v2 (port of Bref's {@code StoreKitService.fetchActiveSubscriptions} /
 * {@code getProductIdMap}): {@code GET /v2/projects/{project}/customers/{id}/subscriptions} and the product map
 * ({@code /v2/projects/{project}/products}, RevenueCat product id → App Store identifier) cached one hour and refreshed
 * once when a product is missing (a product created meanwhile). Pages of 100, at most {@value #MAX_PAGES} pages. Bearer
 * secret key; errors carry the HTTP status only (never the key or the body).
 */
public class RevenueCatRestClient implements RevenueCatClient {

    private static final Logger log = LoggerFactory.getLogger(RevenueCatRestClient.class);
    static final int MAX_PAGES = 10;
    static final Duration PRODUCT_TTL = Duration.ofHours(1);
    static final Duration FORCED_RELOAD_GAP = Duration.ofMinutes(1);

    private final RestClient http;
    private final String projectId;
    private final Clock clock;
    private volatile Map<String, String> products = Map.of();
    private volatile Instant productsLoadedAt = Instant.EPOCH;

    public RevenueCatRestClient(RestClient http, String projectId, Clock clock) {
        this.http = http;
        this.projectId = projectId;
        this.clock = clock;
    }

    @Override
    public boolean enabled() {
        return true;
    }

    @Override
    public List<StoreSubscription> subscriptions(String appUserId) {
        String base = "/v2/projects/" + segment(projectId) + "/customers/" + segment(appUserId) + "/subscriptions";
        List<JsonNode> items = pages(base + "?limit=100");
        Map<String, String> map = productMap(false);
        boolean missing = items.stream().anyMatch(i -> !map.containsKey(i.path("product_id").asText("")));
        Map<String, String> productIds = missing ? productMap(true) : map;
        List<StoreSubscription> out = new ArrayList<>();
        for (JsonNode item : items) {
            String storeProduct = productIds.get(item.path("product_id").asText(""));
            String original = text(item.path("store_subscription_identifier"));
            if (storeProduct == null || original == null) {
                continue;
            }
            long end = item.path("current_period_ends_at").asLong(0);
            String status = item.path("status").asText("").toLowerCase(Locale.ROOT);
            String renewal = item.path("auto_renewal_status").asText("will_renew").toLowerCase(Locale.ROOT);
            out.add(new StoreSubscription(original, storeProduct,
                    item.path("store").asText("").toUpperCase(Locale.ROOT), item.path("gives_access").asBoolean(false),
                    end > 0 ? Instant.ofEpochMilli(end) : null,
                    "sandbox".equalsIgnoreCase(item.path("environment").asText("")),
                    "will_renew".equals(renewal) || renewal.isEmpty(),
                    "in_grace_period".equals(status) || "in_billing_retry".equals(status)));
        }
        return out;
    }

    private Map<String, String> productMap(boolean force) {
        Instant now = clock.instant();
        if (fresh(force, now)) {
            return products;
        }
        synchronized (this) {
            if (fresh(force, now)) { // another caller reloaded meanwhile
                return products;
            }
            Map<String, String> map = new HashMap<>();
            for (JsonNode p : pages("/v2/projects/" + segment(projectId) + "/products?limit=100")) {
                String id = text(p.path("id"));
                String storeId = text(p.path("store_identifier"));
                if (id != null && storeId != null) {
                    map.put(id, storeId);
                }
            }
            products = Map.copyOf(map);
            productsLoadedAt = now;
            return products;
        }
    }

    /**
     * The cached map serves: within its TTL, or — for a reload forced by a missing product — when it was reloaded less
     * than {@link #FORCED_RELOAD_GAP} ago (a product unknown to RevenueCat's map, such as a Play Store one, would
     * otherwise reload the whole list on every call).
     */
    private boolean fresh(boolean force, Instant now) {
        if (products.isEmpty()) {
            return false;
        }
        Duration age = Duration.between(productsLoadedAt, now);
        return force ? age.compareTo(FORCED_RELOAD_GAP) < 0 : age.compareTo(PRODUCT_TTL) < 0;
    }

    private List<JsonNode> pages(String first) {
        List<JsonNode> items = new ArrayList<>();
        String next = first;
        for (int page = 0; next != null && page < MAX_PAGES; page++) {
            JsonNode body = get(next);
            body.path("items").forEach(items::add);
            String nextPage = text(body.path("next_page"));
            next = nextPage != null && nextPage.startsWith("/v2/") ? nextPage : null;
        }
        if (next != null) {
            log.warn("[AppStore] RevenueCat list truncated after {} pages", MAX_PAGES);
        }
        return items;
    }

    private JsonNode get(String uri) {
        try {
            JsonNode body = http.get().uri(uri).retrieve().body(JsonNode.class);
            if (body == null) {
                throw new RevenueCatUnavailableException("RevenueCat empty answer", false);
            }
            return body;
        } catch (org.springframework.web.client.RestClientResponseException e) {
            log.warn("[AppStore] RevenueCat answered HTTP {}", e.getStatusCode().value());
            throw new RevenueCatUnavailableException("RevenueCat HTTP " + e.getStatusCode().value(), false);
        } catch (RestClientException e) {
            log.warn("[AppStore] RevenueCat unreachable ({})", e.getClass().getSimpleName());
            throw new RevenueCatUnavailableException("RevenueCat unreachable", false);
        }
    }

    private static String segment(String raw) {
        return UriUtils.encodePathSegment(raw, StandardCharsets.UTF_8);
    }

    private static String text(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        String s = node.asText("").trim();
        return s.isEmpty() ? null : s;
    }
}
