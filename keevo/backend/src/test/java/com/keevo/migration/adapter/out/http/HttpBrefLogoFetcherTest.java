package com.keevo.migration.adapter.out.http;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.URI;

import static org.assertj.core.api.Assertions.assertThat;

/** Story 24.2 — what the logo downloader accepts (no network call in this test). */
@DisplayName("24.2 — Bref logo download: allow-list and Cloudinary resizing")
class HttpBrefLogoFetcherTest {

    @Test
    @DisplayName("https on an allowed host only; no user info, no explicit port; off unless switched on")
    void allowList() {
        HttpBrefLogoFetcher fetcher = new HttpBrefLogoFetcher(false, "res.cloudinary.com, cdn.example.test", 1000);
        assertThat(fetcher.enabled()).isFalse();
        assertThat(fetcher.fetch("https://res.cloudinary.com/x/image/upload/a.png")).isEmpty(); // switched off
        assertThat(fetcher.allowed("https://res.cloudinary.com/x/image/upload/a.png")).isTrue();
        assertThat(fetcher.allowed("https://CDN.example.test/a.png")).isTrue();
        assertThat(fetcher.allowed("http://res.cloudinary.com/a.png")).isFalse();
        assertThat(fetcher.allowed("https://evil.test/a.png")).isFalse();
        assertThat(fetcher.allowed("https://res.cloudinary.com.evil.test/a.png")).isFalse();
        assertThat(fetcher.allowed("https://user@res.cloudinary.com/a.png")).isFalse();
        assertThat(fetcher.allowed("https://res.cloudinary.com:8443/a.png")).isFalse();
        assertThat(fetcher.allowed("https://169.254.169.254/latest")).isFalse();
        assertThat(fetcher.allowed(null)).isFalse();
        assertThat(fetcher.allowed("not a url")).isFalse();
    }

    @Test
    @DisplayName("a Cloudinary delivery URL is asked at most 512 px wide, once")
    void resized() {
        URI plain = URI.create("https://res.cloudinary.com/demo/image/upload/v1/shops/logo.png");
        URI once = HttpBrefLogoFetcher.resized(plain);
        assertThat(once.toString())
                .isEqualTo("https://res.cloudinary.com/demo/image/upload/c_limit,w_512/v1/shops/logo.png");
        assertThat(HttpBrefLogoFetcher.resized(once)).isEqualTo(once);
        URI other = URI.create("https://cdn.example.test/image/upload/logo.png");
        assertThat(HttpBrefLogoFetcher.resized(other)).isEqualTo(other);
    }
}
