package com.keevo.subscription.offer.application.service;

import com.keevo.identity.api.PlanKind;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import com.keevo.shared.domain.time.KeevoZones;
import com.keevo.subscription.offer.domain.model.Discount;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Map;

/** Story 21.10 — French wording and errors of the offer catalogue (labels only, never a code or an id). */
final class OfferText {

    static final String PLAN_NOT_FOUND = "Plan introuvable";
    static final String CYCLE_NOT_FOUND = "Durée introuvable";
    static final String DISCOUNT_NOT_FOUND = "Remise introuvable";
    static final String PRICE_NOT_FOUND = "Prix introuvable";
    static final String TENANT_NOT_FOUND = "Entreprise introuvable";
    static final String DUPLICATE_CODE = "Ce code existe déjà";
    static final String PLAN_IN_USE = "Ce plan est attribué à des entreprises : sa nature ne peut plus changer";
    static final String PLAN_IN_SETTINGS =
            "Ce plan est utilisé par les réglages de l'offre : changez d'abord les réglages";
    static final String PLAN_HAS_PRICES = "Ce plan a des prix : supprimez-les avant de changer sa nature";
    static final String CYCLE_IN_USE = "Cette durée est utilisée : créez une nouvelle durée";
    static final String PRICE_ONLY_PAID = "Seuls les plans payants ont un prix";
    static final String AMOUNT_RANGE = "Le montant doit être compris entre 1 et 100 000 000 FCFA";
    static final String OFFER_NOT_OFFERED = "Cette offre n'est pas proposée";
    static final String CYCLE_NOT_OFFERED = "Cette durée n'est pas proposée";
    static final String NO_PRICE = "Cette offre n'a pas de prix pour cette durée";
    static final String PROMO_INVALID = "Code promo invalide ou expiré";
    static final String SEED_AUTHOR = "Données de départ";
    static final String SUPER_ADMIN = "Super admin";
    static final String SYSTEM = "Système";

    static final long MAX_AMOUNT_XAF = 100_000_000L;

    private static final DateTimeFormatter FR_DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private OfferText() {
    }

    static DomainException invalid(String field, String message) {
        return DomainException.userFacing(ErrorCode.VALIDATION_ERROR, message, Map.of(field, message));
    }

    static DomainException notFound(String message) {
        return DomainException.userFacing(ErrorCode.NOT_FOUND, message);
    }

    static DomainException conflict(String message) {
        return DomainException.userFacing(ErrorCode.INVALID_STATE, message);
    }

    /** French name of a plan kind. */
    static String kindLabel(PlanKind kind) {
        return switch (kind) {
            case FREE -> "Gratuit";
            case TRIAL -> "Essai";
            case PAID -> "Payant";
        };
    }

    /** {@code 3 500 FCFA}. */
    static String xaf(long amount) {
        String digits = Long.toString(Math.abs(amount));
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < digits.length(); i++) {
            if (i > 0 && (digits.length() - i) % 3 == 0) {
                out.append(' ');
            }
            out.append(digits.charAt(i));
        }
        return (amount < 0 ? "-" : "") + out + " FCFA";
    }

    /** {@code 10 %}, {@code 12,5 %}. */
    static String percent(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString().replace('.', ',') + " %";
    }

    /** The value of a discount: « 10 % » or « 1 000 FCFA ». */
    static String value(Discount d) {
        return d.percentOff() != null ? percent(d.percentOff()) : xaf(d.amountOffXaf() == null ? 0 : d.amountOffXaf());
    }

    /** {@code 30/11/2026} (WAT). */
    static String date(Instant instant) {
        return FR_DATE.format(LocalDate.ofInstant(instant, KeevoZones.WAT));
    }

    /** Upper-cased trimmed code, {@code null} when blank. */
    static String code(String raw) {
        return raw == null || raw.isBlank() ? null : raw.trim().toUpperCase(Locale.ROOT);
    }

    static String blankToNull(String raw) {
        return raw == null || raw.isBlank() ? null : raw.trim();
    }

    /** « « Pro » ». */
    static String quoted(String label) {
        return "« " + label + " »";
    }
}
