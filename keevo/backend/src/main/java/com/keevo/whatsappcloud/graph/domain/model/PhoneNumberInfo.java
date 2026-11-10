package com.keevo.whatsappcloud.graph.domain.model;

/** Story 21.5 — a WhatsApp Business number as Meta describes it ({@code display_phone_number}, {@code verified_name}). */
public record PhoneNumberInfo(String id, String displayPhoneNumber, String verifiedName) {
}
