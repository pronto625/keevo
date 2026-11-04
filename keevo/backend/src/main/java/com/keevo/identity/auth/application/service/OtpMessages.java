package com.keevo.identity.auth.application.service;

import java.util.Locale;

/**
 * Story 19.2 — texts of a sign-in code, port of Bref's {@code otp-messages.ts} for two locales: French (default) and
 * English (when {@code Accept-Language} starts with {@code en}). Brand « Bref Point ». The HTML body has no external
 * asset and the code is escaped.
 */
public final class OtpMessages {

    public enum Lang { FR, EN }

    private OtpMessages() {
    }

    /** English when the header starts with {@code en}, French otherwise. */
    public static Lang resolve(String acceptLanguage) {
        return acceptLanguage != null && acceptLanguage.strip().toLowerCase(Locale.ROOT).startsWith("en")
                ? Lang.EN : Lang.FR;
    }

    /** SMS and WhatsApp text. */
    public static String sms(Lang lang, String code) {
        return lang == Lang.EN
                ? "Your Bref Point login code: " + code + ". Valid for 5 minutes. Do not share it."
                : "Votre code de connexion Bref Point : " + code + ". Valable 5 minutes. Ne le partagez pas.";
    }

    public static String emailSubject(Lang lang) {
        return lang == Lang.EN ? "Your Bref Point verification code" : "Votre code de vérification Bref Point";
    }

    public static String emailText(Lang lang, String code) {
        return lang == Lang.EN
                ? "Your verification code: " + code + "\nValid for 5 minutes. Do not share it."
                : "Votre code de vérification : " + code + "\nValable 5 minutes. Ne le partagez pas.";
    }

    public static String emailHtml(Lang lang, String code) {
        boolean en = lang == Lang.EN;
        String title = en ? "Verification Code" : "Code de vérification";
        String body = en ? "Use the code below to sign in to your Bref Point account."
                : "Utilisez le code ci-dessous pour vous connecter à votre compte Bref Point.";
        String expiry = en ? "This code expires in <strong>5 minutes</strong>. Never share it."
                : "Ce code expire dans <strong>5 minutes</strong>. Ne le partagez jamais.";
        String disclaimer = en ? "If you didn't request this code, ignore this email."
                : "Si vous n'avez pas demandé ce code, ignorez cet email.";
        return "<!DOCTYPE html><html lang=\"" + (en ? "en" : "fr") + "\"><head><meta charset=\"utf-8\">"
                + "<title>" + escape(title) + "</title></head>"
                + "<body style=\"margin:0;padding:24px;background:#F1F5F9;font-family:Arial,Helvetica,sans-serif;color:#0F172A;\">"
                + "<div style=\"max-width:480px;margin:0 auto;background:#FFFFFF;border-radius:12px;padding:32px;\">"
                + "<h1 style=\"font-size:20px;margin:0 0 16px;\">" + escape(title) + "</h1>"
                + "<p style=\"font-size:15px;line-height:1.5;margin:0 0 24px;\">" + escape(body) + "</p>"
                + "<div style=\"background:#F8FAFC;border:2px solid #FFBD18;border-radius:10px;padding:24px;"
                + "text-align:center;margin-bottom:24px;font-size:32px;font-weight:bold;letter-spacing:8px;\">"
                + escape(code) + "</div>"
                + "<p style=\"font-size:14px;margin:0 0 12px;\">" + expiry + "</p>"
                + "<p style=\"font-size:12px;color:#64748B;margin:0;\">" + escape(disclaimer) + "</p>"
                + "</div></body></html>";
    }

    /** Minimal HTML escaping ({@code & < > " '}); the application layer has no web dependency. */
    static String escape(String value) {
        if (value == null) {
            return "";
        }
        StringBuilder out = new StringBuilder(value.length());
        for (char c : value.toCharArray()) {
            switch (c) {
                case '&' -> out.append("&amp;");
                case '<' -> out.append("&lt;");
                case '>' -> out.append("&gt;");
                case '"' -> out.append("&quot;");
                case '\'' -> out.append("&#39;");
                default -> out.append(c);
            }
        }
        return out.toString();
    }
}
