package com.keevo.whatsappcloud.campaign.domain.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Story 21.11 — the purchase-behaviour criteria of a campaign (Bref {@code targetingCriteria}), relative to the moment
 * the audience is computed. Each is optional ({@code null} = not set); bounds are inclusive.
 *
 * @param inactiveForDays     no purchase for this many days (never-buyers included), 1..3650
 * @param purchasedWithinDays a purchase in the last this many days, 1..3650
 * @param totalSpentMin       net total of the purchases ≥ (FCFA)
 * @param totalSpentMax       net total of the purchases ≤ (FCFA)
 * @param totalInvoicesMin    number of purchases ≥ (sales + invoices; Bref's name)
 * @param totalInvoicesMax    number of purchases ≤
 */
public record PurchaseCriteria(Integer inactiveForDays, Integer purchasedWithinDays, Long totalSpentMin,
                               Long totalSpentMax, Integer totalInvoicesMin, Integer totalInvoicesMax) {

    public static final PurchaseCriteria NONE = new PurchaseCriteria(null, null, null, null, null, null);

    public static final String INACTIVE_FOR_DAYS = "inactiveForDays";
    public static final String PURCHASED_WITHIN_DAYS = "purchasedWithinDays";
    public static final String TOTAL_SPENT_MIN = "totalSpentMin";
    public static final String TOTAL_SPENT_MAX = "totalSpentMax";
    public static final String TOTAL_INVOICES_MIN = "totalInvoicesMin";
    public static final String TOTAL_INVOICES_MAX = "totalInvoicesMax";
    /** The JSON fields, Bref's spellings. */
    public static final List<String> FIELDS = List.of(INACTIVE_FOR_DAYS, PURCHASED_WITHIN_DAYS, TOTAL_SPENT_MIN,
            TOTAL_SPENT_MAX, TOTAL_INVOICES_MIN, TOTAL_INVOICES_MAX);

    public static final int MAX_DAYS = 3_650;
    public static final long MAX_AMOUNT = 1_000_000_000_000L;
    public static final int MAX_COUNT = 10_000_000;

    /** A French reason the criteria are refused, with the JSON field it concerns. */
    public record Problem(String field, String message) {
    }

    public boolean isEmpty() {
        return inactiveForDays == null && purchasedWithinDays == null && totalSpentMin == null && totalSpentMax == null
                && totalInvoicesMin == null && totalInvoicesMax == null;
    }

    /** The first reason these criteria cannot be saved, or {@code null}. */
    public Problem problem() {
        if (!inRange(inactiveForDays, 1, MAX_DAYS)) {
            return new Problem(INACTIVE_FOR_DAYS, "Le nombre de jours sans achat doit être compris entre 1 et 3 650");
        }
        if (!inRange(purchasedWithinDays, 1, MAX_DAYS)) {
            return new Problem(PURCHASED_WITHIN_DAYS, "Le nombre de jours depuis le dernier achat doit être compris "
                    + "entre 1 et 3 650");
        }
        if (!inRange(totalSpentMin, 0, MAX_AMOUNT) || !inRange(totalSpentMax, 0, MAX_AMOUNT)) {
            return new Problem(totalSpentMin != null && !inRange(totalSpentMin, 0, MAX_AMOUNT) ? TOTAL_SPENT_MIN
                    : TOTAL_SPENT_MAX, "Le montant des achats doit être compris entre 0 et 1 000 000 000 000 FCFA");
        }
        if (!inRange(totalInvoicesMin, 0, MAX_COUNT) || !inRange(totalInvoicesMax, 0, MAX_COUNT)) {
            return new Problem(totalInvoicesMin != null && !inRange(totalInvoicesMin, 0, MAX_COUNT)
                    ? TOTAL_INVOICES_MIN : TOTAL_INVOICES_MAX, "Le nombre d'achats doit être compris entre 0 et "
                    + "10 000 000");
        }
        if (totalSpentMin != null && totalSpentMax != null && totalSpentMin > totalSpentMax) {
            return new Problem(TOTAL_SPENT_MIN, "Le montant minimum des achats ne peut pas dépasser le maximum");
        }
        if (totalInvoicesMin != null && totalInvoicesMax != null && totalInvoicesMin > totalInvoicesMax) {
            return new Problem(TOTAL_INVOICES_MIN, "Le nombre minimum d'achats ne peut pas dépasser le maximum");
        }
        if (inactiveForDays != null && purchasedWithinDays != null && purchasedWithinDays <= inactiveForDays) {
            return new Problem(PURCHASED_WITHIN_DAYS, "Aucun client ne peut avoir acheté dans les "
                    + purchasedWithinDays + " derniers jours sans achat depuis " + inactiveForDays + " jours");
        }
        return null;
    }

    /** These criteria with the fields of {@code patch} named in {@code present} (a present {@code null} clears). */
    public PurchaseCriteria merge(PurchaseCriteria patch, Set<String> present) {
        PurchaseCriteria p = patch == null ? NONE : patch;
        return new PurchaseCriteria(
                present.contains(INACTIVE_FOR_DAYS) ? p.inactiveForDays : inactiveForDays,
                present.contains(PURCHASED_WITHIN_DAYS) ? p.purchasedWithinDays : purchasedWithinDays,
                present.contains(TOTAL_SPENT_MIN) ? p.totalSpentMin : totalSpentMin,
                present.contains(TOTAL_SPENT_MAX) ? p.totalSpentMax : totalSpentMax,
                present.contains(TOTAL_INVOICES_MIN) ? p.totalInvoicesMin : totalInvoicesMin,
                present.contains(TOTAL_INVOICES_MAX) ? p.totalInvoicesMax : totalInvoicesMax);
    }

    /** The criteria as French sentences, for the campaign screens (empty when none). */
    public List<String> labels() {
        List<String> out = new ArrayList<>(4);
        if (inactiveForDays != null) {
            out.add("Sans achat depuis " + days(inactiveForDays));
        }
        if (purchasedWithinDays != null) {
            out.add(purchasedWithinDays == 1 ? "Achat au cours du dernier jour"
                    : "Achat au cours des " + amount(purchasedWithinDays) + " derniers jours");
        }
        if (totalSpentMin != null && totalSpentMax != null) {
            out.add("Total des achats entre " + amount(totalSpentMin) + " et " + amount(totalSpentMax) + " FCFA");
        } else if (totalSpentMin != null) {
            out.add("Total des achats d'au moins " + amount(totalSpentMin) + " FCFA");
        } else if (totalSpentMax != null) {
            out.add("Total des achats de " + amount(totalSpentMax) + " FCFA au plus");
        }
        if (totalInvoicesMin != null && totalInvoicesMax != null) {
            out.add(totalInvoicesMin.equals(totalInvoicesMax) ? "Exactement " + purchases(totalInvoicesMin)
                    : "Entre " + amount(totalInvoicesMin) + " et " + purchases(totalInvoicesMax));
        } else if (totalInvoicesMin != null) {
            out.add("Au moins " + purchases(totalInvoicesMin));
        } else if (totalInvoicesMax != null) {
            out.add(totalInvoicesMax == 0 ? "Aucun achat" : purchases(totalInvoicesMax) + " au plus");
        }
        return out;
    }

    private static boolean inRange(Number v, long min, long max) {
        return v == null || (v.longValue() >= min && v.longValue() <= max);
    }

    private static String days(int n) {
        return n == 1 ? "1 jour" : amount(n) + " jours";
    }

    private static String purchases(long n) {
        return amount(n) + (n > 1 ? " achats" : " achat");
    }

    /** {@code 15000} → {@code 15 000} (plain spaces, as the app shows amounts). */
    static String amount(long n) {
        String digits = Long.toString(Math.abs(n));
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < digits.length(); i++) {
            if (i > 0 && (digits.length() - i) % 3 == 0) {
                sb.append(' ');
            }
            sb.append(digits.charAt(i));
        }
        return (n < 0 ? "-" : "") + sb;
    }
}
