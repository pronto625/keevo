package com.keevo.messaging.notification.domain.model;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Story 19.9b — the browsers' official push services (port of Bref's {@code push-endpoint.ts}): FCM (Chrome, Edge,
 * Opera, Android), Mozilla autopush, Apple web push (Safari), WNS (Edge / Windows). The endpoint is called by the
 * server, so any other address is refused (SSRF): https only, no user info, port 443 only, at most 2048 characters.
 */
public final class PushEndpoints {

    public static final int MAX_LENGTH = 2048;

    private static final Set<String> EXACT_HOSTS = Set.of(
            "fcm.googleapis.com",
            "updates.push.services.mozilla.com",
            "updates-autopush.stage.mozaws.net",
            "updates-autopush.dev.mozaws.net");
    private static final List<String> HOST_SUFFIXES = List.of(".push.apple.com", ".notify.windows.com");

    private PushEndpoints() {
    }

    public static boolean isAllowed(String endpoint) {
        if (endpoint == null || endpoint.isEmpty() || endpoint.length() > MAX_LENGTH) return false;
        URI uri;
        try {
            uri = new URI(endpoint);
        } catch (URISyntaxException e) {
            return false;
        }
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getRawUserInfo() != null) return false;
        if (uri.getPort() != -1 && uri.getPort() != 443) return false;
        String host = uri.getHost();
        if (host == null) return false;
        host = host.toLowerCase(Locale.ROOT);
        if (EXACT_HOSTS.contains(host)) return true;
        for (String suffix : HOST_SUFFIXES) {
            if (host.endsWith(suffix)) return true;
        }
        return false;
    }

    /** {@code https://host[:port]} of the endpoint: the {@code aud} claim of its VAPID token. */
    public static String origin(String endpoint) {
        URI uri = URI.create(endpoint);
        String host = uri.getHost().toLowerCase(Locale.ROOT);
        return "https://" + (uri.getPort() == -1 || uri.getPort() == 443 ? host : host + ":" + uri.getPort());
    }
}
