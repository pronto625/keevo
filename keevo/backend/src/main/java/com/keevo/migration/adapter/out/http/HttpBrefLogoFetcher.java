package com.keevo.migration.adapter.out.http;

import com.keevo.migration.domain.port.out.BrefLogoFetcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Story 24.2 — downloads a Bref shop logo (decision D5): switched off by default
 * ({@code keevo.migration.bref.logo.fetch-enabled}), https only, exact host allow-list
 * ({@code keevo.migration.bref.logo.allowed-hosts}, default {@code res.cloudinary.com}), no redirect followed, an
 * {@code image/*} answer of at most {@code max-bytes} read within 15 s. A Cloudinary image is asked resized
 * ({@code c_limit,w_512}) so it fits the store logo limit. Nothing is logged about the URL beyond its host.
 */
@Component
class HttpBrefLogoFetcher implements BrefLogoFetcher {

    private static final Logger log = LoggerFactory.getLogger(HttpBrefLogoFetcher.class);
    private static final String CLOUDINARY_UPLOAD = "/image/upload/";
    static final String CLOUDINARY_RESIZE = "c_limit,w_512/";
    /** Deadline of the whole download (headers and body). */
    static final Duration TIMEOUT = Duration.ofSeconds(15);

    private final boolean enabled;
    private final Set<String> hosts;
    private final int maxBytes;
    private final HttpClient client;

    HttpBrefLogoFetcher(@Value("${keevo.migration.bref.logo.fetch-enabled:false}") boolean enabled,
                        @Value("${keevo.migration.bref.logo.allowed-hosts:res.cloudinary.com}") String hosts,
                        @Value("${keevo.migration.bref.logo.max-bytes:2097152}") int maxBytes) {
        this.enabled = enabled;
        this.hosts = Arrays.stream(hosts.split(",")).map(String::strip).filter(h -> !h.isEmpty())
                .map(h -> h.toLowerCase(Locale.ROOT)).collect(Collectors.toUnmodifiableSet());
        this.maxBytes = maxBytes;
        this.client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NEVER).build();
    }

    @Override
    public boolean enabled() {
        return enabled;
    }

    @Override
    public boolean allowed(String url) {
        return uri(url).isPresent();
    }

    @Override
    public Optional<byte[]> fetch(String url) {
        Optional<URI> target = uri(url).map(HttpBrefLogoFetcher::resized);
        if (!enabled || target.isEmpty()) {
            return Optional.empty();
        }
        HttpRequest request = HttpRequest.newBuilder(target.get()).timeout(TIMEOUT).GET().build();
        // the whole exchange (headers and body) is bounded: the request time-out covers the headers only, so the
        // body is read inside the same deadline and the exchange is cancelled past it (the stream is closed)
        java.util.concurrent.CompletableFuture<HttpResponse<InputStream>> exchange =
                client.sendAsync(request, HttpResponse.BodyHandlers.ofInputStream());
        java.util.concurrent.CompletableFuture<Optional<byte[]>> read = exchange.thenApply(this::readImage);
        try {
            return read.get(TIMEOUT.toSeconds(), java.util.concurrent.TimeUnit.SECONDS);
        } catch (java.util.concurrent.TimeoutException | java.util.concurrent.ExecutionException e) {
            log.info("Bref logo download failed from {} ({})", target.get().getHost(),
                    e.getCause() == null ? e.getClass().getSimpleName() : e.getCause().getClass().getSimpleName());
            exchange.thenAccept(r -> closeQuietly(r.body()));
            exchange.cancel(true);
            read.cancel(true);
            return Optional.empty();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            exchange.cancel(true);
            return Optional.empty();
        }
    }

    private Optional<byte[]> readImage(HttpResponse<InputStream> response) {
        try (InputStream body = response.body()) {
            String type = response.headers().firstValue("Content-Type").orElse("");
            if (response.statusCode() != 200 || !type.toLowerCase(Locale.ROOT).startsWith("image/")) {
                log.info("Bref logo download refused by {} (status {})", response.uri().getHost(),
                        response.statusCode());
                return Optional.empty();
            }
            byte[] bytes = body.readNBytes(maxBytes + 1);
            return bytes.length == 0 || bytes.length > maxBytes ? Optional.empty() : Optional.of(bytes);
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    private static void closeQuietly(InputStream in) {
        try {
            in.close();
        } catch (IOException ignored) {
            // nothing more to do
        }
    }

    /** The URL when it is https on an allowed host, without user info nor explicit port. */
    private Optional<URI> uri(String url) {
        if (url == null || url.isBlank()) {
            return Optional.empty();
        }
        try {
            URI uri = new URI(url.strip());
            String host = uri.getHost();
            if (!"https".equalsIgnoreCase(uri.getScheme()) || host == null || uri.getUserInfo() != null
                    || uri.getPort() != -1 || !hosts.contains(host.toLowerCase(Locale.ROOT))) {
                return Optional.empty();
            }
            return Optional.of(uri);
        } catch (URISyntaxException e) {
            return Optional.empty();
        }
    }

    /** A Cloudinary delivery URL asked at most 512 px wide (unchanged when it already carries this transform). */
    static URI resized(URI uri) {
        String raw = uri.toString();
        int at = raw.indexOf(CLOUDINARY_UPLOAD);
        if (!"res.cloudinary.com".equalsIgnoreCase(uri.getHost()) || at < 0
                || raw.startsWith(CLOUDINARY_RESIZE, at + CLOUDINARY_UPLOAD.length())) {
            return uri;
        }
        int cut = at + CLOUDINARY_UPLOAD.length();
        return URI.create(raw.substring(0, cut) + CLOUDINARY_RESIZE + raw.substring(cut));
    }
}
