package com.keevo.messaging.notification.domain.model;

/**
 * Story 19.9b — VAPID keys of Web Push ({@code keevo.push.web.*}, Bref's {@code VAPID_PUBLIC_KEY},
 * {@code VAPID_PRIVATE_KEY}, {@code VAPID_SUBJECT}): base64url uncompressed P-256 public point and private scalar, as
 * the {@code web-push} library generates them. Both keys present = Web Push enabled.
 */
public record WebPushSettings(String publicKey, String privateKey, String subject) {

    public static final String DEFAULT_SUBJECT = "mailto:admin@brefpoint.app";

    public WebPushSettings {
        publicKey = publicKey == null ? "" : publicKey.strip();
        privateKey = privateKey == null ? "" : privateKey.strip();
        subject = subject == null || subject.isBlank() ? DEFAULT_SUBJECT : subject.strip();
    }

    public boolean isEnabled() {
        return !publicKey.isEmpty() && !privateKey.isEmpty();
    }

    /** The public key handed to browsers; empty when Web Push is disabled. */
    public String exposedPublicKey() {
        return isEnabled() ? publicKey : "";
    }
}
