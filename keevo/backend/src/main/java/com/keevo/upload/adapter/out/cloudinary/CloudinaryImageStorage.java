package com.keevo.upload.adapter.out.cloudinary;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.keevo.upload.domain.port.out.ImageStorage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Cloudinary par son API d'envoi signé (comme api-bref) : dossier {@code bref/<dossier>}, image réduite à 1024 px au
 * plus (jamais recadrée ni agrandie), qualité automatique. Sans les trois réglages
 * ({@code keevo.uploads.cloudinary.*}), le stockage est indisponible.
 */
@Component
class CloudinaryImageStorage implements ImageStorage {

    private static final Duration TIMEOUT = Duration.ofSeconds(30);
    private static final String TRANSFORMATION = "c_limit,h_1024,w_1024/q_auto";

    private final String apiBase;
    private final String cloudName;
    private final String apiKey;
    private final String apiSecret;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final ObjectMapper mapper = new ObjectMapper();

    CloudinaryImageStorage(@Value("${keevo.uploads.cloudinary.api-base:https://api.cloudinary.com}") String apiBase,
                           @Value("${keevo.uploads.cloudinary.cloud-name:}") String cloudName,
                           @Value("${keevo.uploads.cloudinary.api-key:}") String apiKey,
                           @Value("${keevo.uploads.cloudinary.api-secret:}") String apiSecret) {
        this.apiBase = apiBase.strip().replaceAll("/+$", "");
        this.cloudName = cloudName.strip();
        this.apiKey = apiKey.strip();
        this.apiSecret = apiSecret.strip();
    }

    @Override
    public boolean available() {
        return !cloudName.isEmpty() && !apiKey.isEmpty() && !apiSecret.isEmpty();
    }

    @Override
    public StoredImage store(byte[] content, String folder) throws IOException {
        String timestamp = Long.toString(System.currentTimeMillis() / 1000);
        // signature : paramètres signés triés par nom, joints par &, puis le secret, le tout en SHA-1
        Map<String, String> signed = new TreeMap<>();
        signed.put("folder", "bref/" + folder);
        signed.put("timestamp", timestamp);
        signed.put("transformation", TRANSFORMATION);
        StringBuilder toSign = new StringBuilder();
        signed.forEach((k, v) -> toSign.append(toSign.length() == 0 ? "" : "&").append(k).append('=').append(v));
        String signature = sha1(toSign + apiSecret);

        String boundary = "----keevo" + UUID.randomUUID().toString().replace("-", "");
        ByteArrayOutputStream body = new ByteArrayOutputStream(content.length + 1024);
        signed.forEach((k, v) -> field(body, boundary, k, v));
        field(body, boundary, "api_key", apiKey);
        field(body, boundary, "signature", signature);
        write(body, "--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"image\"\r\n"
                + "Content-Type: application/octet-stream\r\n\r\n");
        body.writeBytes(content);
        write(body, "\r\n--" + boundary + "--\r\n");

        HttpRequest request = HttpRequest.newBuilder(URI.create(apiBase + "/v1_1/" + cloudName
                        + "/image/upload"))
                .timeout(TIMEOUT)
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray()))
                .build();
        try {
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                throw new IOException("Cloudinary a répondu " + response.statusCode());
            }
            JsonNode json = mapper.readTree(response.body());
            String url = json.path("secure_url").asText("");
            String publicId = json.path("public_id").asText("");
            if (url.isEmpty() || publicId.isEmpty()) {
                throw new IOException("Réponse de Cloudinary incomplète");
            }
            return new StoredImage(url, publicId);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Envoi interrompu", e);
        }
    }

    private static void field(ByteArrayOutputStream out, String boundary, String name, String value) {
        write(out, "--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + name + "\"\r\n\r\n" + value
                + "\r\n");
    }

    private static void write(ByteArrayOutputStream out, String text) {
        out.writeBytes(text.getBytes(StandardCharsets.UTF_8));
    }

    private static String sha1(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
