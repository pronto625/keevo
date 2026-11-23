package com.keevo.upload.adapter.out.cloudinary;

import com.keevo.upload.domain.port.out.ImageStorage.StoredImage;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Story 22.19 — contract of the signed upload against a local stand-in of Cloudinary's upload API
 * (https://cloudinary.com/documentation/upload_images#authenticated_requests): POST
 * {@code /v1_1/<cloud>/image/upload}, multipart fields {@code file}, {@code api_key}, {@code timestamp},
 * {@code signature} and the signed parameters; signature = SHA-1 of the signed parameters sorted by name, joined by
 * {@code &}, followed by the API secret ({@code api_key}, {@code file} and {@code signature} excluded).
 * A run against the real service needs the CLOUDINARY_* keys.
 */
class CloudinaryImageStorageContractTest {

    private HttpServer server;
    private final AtomicReference<String> path = new AtomicReference<>();
    private final AtomicReference<String> contentType = new AtomicReference<>();
    private final AtomicReference<byte[]> body = new AtomicReference<>();
    private volatile int status = 200;
    private volatile String answer = "{\"secure_url\":\"https://res.cloudinary.com/demo/image/upload/v1/bref/KV-ABC123/products/x.png\","
            + "\"public_id\":\"bref/KV-ABC123/products/x\"}";

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            path.set(exchange.getRequestURI().getPath());
            contentType.set(exchange.getRequestHeaders().getFirst("Content-Type"));
            body.set(exchange.getRequestBody().readAllBytes());
            byte[] out = answer.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, out.length);
            exchange.getResponseBody().write(out);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private CloudinaryImageStorage storage() {
        return new CloudinaryImageStorage("http://127.0.0.1:" + server.getAddress().getPort() + "/", "demo",
                "123456789", "s3cr3t");
    }

    private static Map<String, String> fields(byte[] raw, String boundary) {
        String text = new String(raw, StandardCharsets.ISO_8859_1);
        Map<String, String> out = new LinkedHashMap<>();
        Matcher m = Pattern.compile("--" + Pattern.quote(boundary)
                + "\\r\\nContent-Disposition: form-data; name=\"([^\"]+)\"(?:; filename=\"[^\"]*\")?\\r\\n"
                + "(?:Content-Type: [^\\r]+\\r\\n)?\\r\\n(.*?)\\r\\n(?=--" + Pattern.quote(boundary) + ")",
                Pattern.DOTALL).matcher(text);
        while (m.find()) {
            out.put(m.group(1), m.group(2));
        }
        return out;
    }

    private static String sha1(String s) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(s.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    @DisplayName("signed multipart POST to /v1_1/<cloud>/image/upload; secure_url and public_id read back")
    void signedUpload() throws Exception {
        byte[] png = {(byte) 0x89, 'P', 'N', 'G', 1, 2, 3};

        StoredImage stored = storage().store(png, "KV-ABC123/products");

        assertThat(stored.url()).startsWith("https://res.cloudinary.com/");
        assertThat(stored.publicId()).isEqualTo("bref/KV-ABC123/products/x");
        assertThat(path.get()).isEqualTo("/v1_1/demo/image/upload");
        assertThat(contentType.get()).startsWith("multipart/form-data; boundary=");
        String boundary = contentType.get().substring("multipart/form-data; boundary=".length());
        Map<String, String> f = fields(body.get(), boundary);
        assertThat(f).containsKeys("file", "api_key", "timestamp", "signature", "folder", "transformation");
        assertThat(f.get("api_key")).isEqualTo("123456789");
        assertThat(f.get("folder")).isEqualTo("bref/KV-ABC123/products");
        assertThat(f.get("file").getBytes(StandardCharsets.ISO_8859_1)).isEqualTo(png);
        String expected = sha1("folder=" + f.get("folder") + "&timestamp=" + f.get("timestamp")
                + "&transformation=" + f.get("transformation") + "s3cr3t");
        assertThat(f.get("signature")).isEqualTo(expected);
        assertThat(Long.parseLong(f.get("timestamp"))).isCloseTo(System.currentTimeMillis() / 1000, org.assertj.core.data.Offset.offset(60L));
    }

    @Test
    @DisplayName("a refusal of Cloudinary (401 bad signature) is an IOException, not a stored image")
    void refused() {
        status = 401;
        answer = "{\"error\":{\"message\":\"Invalid Signature\"}}";
        assertThatThrownBy(() -> storage().store(new byte[] {1}, "x")).isInstanceOf(IOException.class)
                .hasMessageContaining("401");
    }

    @Test
    @DisplayName("an answer without secure_url / public_id is an IOException")
    void incompleteAnswer() {
        answer = "{}";
        assertThatThrownBy(() -> storage().store(new byte[] {1}, "x")).isInstanceOf(IOException.class);
    }

    @Test
    @DisplayName("not available without the three keys")
    void unavailable() {
        assertThat(new CloudinaryImageStorage("https://api.cloudinary.com", "", "k", "s").available()).isFalse();
        assertThat(new CloudinaryImageStorage("https://api.cloudinary.com", "c", "k", "s").available()).isTrue();
    }
}
