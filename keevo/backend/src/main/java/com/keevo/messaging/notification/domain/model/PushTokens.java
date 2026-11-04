package com.keevo.messaging.notification.domain.model;

/**
 * Story 19.9 — device token kinds. The React Native app registers Expo tokens ({@code ExponentPushToken[…]}, older
 * {@code ExpoPushToken[…]}), delivered by Expo; every other token is an FCM token of the Flutter V1 app.
 */
public final class PushTokens {

    private PushTokens() {
    }

    public static boolean isExpo(String token) {
        return token != null && (token.startsWith("ExponentPushToken[") || token.startsWith("ExpoPushToken["));
    }
}
