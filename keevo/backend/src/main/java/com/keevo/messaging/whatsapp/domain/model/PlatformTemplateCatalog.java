package com.keevo.messaging.whatsapp.domain.model;

import com.keevo.messaging.whatsapp.domain.model.PlatformTemplate.Category;
import com.keevo.shared.capability.notification.PlatformMessageKind;

import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static com.keevo.shared.capability.notification.PlatformMessageKind.*;

/**
 * Story 19.9c — the catalogue (Registry) of the Meta templates of the Keevo platform number and the mapping of every
 * {@link PlatformMessageKind} to its template. Built once, immutable.
 *
 * <p><b>Wording to be validated by the user</b> before submission in Meta Business Manager (checklist generated in
 * {@code docs/whatsapp/modeles-meta-plateforme.md}). Most templates frame one « detail » variable that carries the
 * message's existing French text (flattened to one line by {@link TemplateParameters}); fixed messages keep their exact
 * free-text wording. A template is used only once declared approved ({@code keevo.whatsapp.meta.approved-templates}).
 *
 * <p>The sign-in code template {@link #OTP} is Bref's, already approved on the shared Meta account; it is listed for
 * completeness and is sent by {@code sendCode} (19.9b), not through a kind.
 */
public final class PlatformTemplateCatalog {

    static final String FOOTER = "Message automatique de Keevo";
    private static final String DETAIL_EXAMPLE_REPORT =
            "Boutique Akwa · Ventes : 12 · Chiffre d'affaires : 85 000 FCFA · Meilleur produit : Riz 5 kg";

    /** Bref's sign-in code template (AUTHENTICATION), already approved; body fixed by Meta. */
    public static final PlatformTemplate OTP = new PlatformTemplate("bref_point_otp", Category.AUTHENTICATION, "fr",
            "*{{1}}* est votre code de vérification. Pour votre sécurité, ne le partagez pas.", null,
            List.of("123456"));

    public static final PlatformTemplate DAILY = detail("keevo_rapport_journalier",
            "Bonjour, voici le rapport de votre journée sur Keevo :\n{{1}}\n\n"
                    + "Retrouvez le détail complet dans l'application Keevo.", DETAIL_EXAMPLE_REPORT);

    public static final PlatformTemplate WEEKLY = detail("keevo_rapport_hebdomadaire",
            "Bonjour, voici le rapport de votre semaine sur Keevo :\n{{1}}\n\n"
                    + "Retrouvez le détail complet dans l'application Keevo.",
            "Semaine du 03/11 au 09/11 · Ventes : 84 · Chiffre d'affaires : 610 000 FCFA");

    public static final PlatformTemplate MULTI_STORE = detail("keevo_rapport_boutiques",
            "Bonjour, voici le récapitulatif du jour de toutes vos boutiques sur Keevo :\n{{1}}\n\n"
                    + "Retrouvez le détail complet dans l'application Keevo.",
            "Boutique Akwa : 85 000 FCFA · Boutique Bonapriso : 42 500 FCFA · Total : 127 500 FCFA");

    public static final PlatformTemplate CLOSURE = detail("keevo_cloture_caisse",
            "Une clôture de caisse vient d'être enregistrée sur Keevo :\n{{1}}\n\n"
                    + "Retrouvez le détail dans l'application Keevo.",
            "Boutique Akwa · Clôture de 18:30 · Espèces : 64 000 FCFA · Mobile Money : 21 000 FCFA");

    public static final PlatformTemplate STOCK = detail("keevo_alerte_stock",
            "Alerte de stock bas sur Keevo :\n{{1}}\n\nPensez à réapprovisionner ces produits.",
            "Riz 5 kg : 3 unité(s) restante(s) (seuil : 5) — Boutique Akwa");

    public static final PlatformTemplate TEAM = detail("keevo_activite_equipe",
            "Nouvelle activité de votre équipe sur Keevo :\n{{1}}\n\nRetrouvez le détail dans l'application Keevo.",
            "Awa a créé 3 produits dans Boutique Akwa : Riz 5 kg, Huile 1 L, Sucre 1 kg");

    public static final PlatformTemplate SALE = detail("keevo_suivi_vente",
            "Suivi des ventes de votre entreprise sur Keevo :\n{{1}}\n\n"
                    + "Retrouvez le détail dans l'application Keevo.",
            "Vente validée · Awa a validé une vente de 12 500 FCFA à Boutique Akwa");

    public static final PlatformTemplate MODULES = detail("keevo_modules",
            "Information sur les modules de votre compte Keevo :\n{{1}}\n\n"
                    + "Pour toute question, contactez le support Keevo.",
            "Votre module Facturation est arrivé à échéance : il reste consultable en lecture seule jusqu'au "
                    + "15/11/2026. Contactez-nous pour le renouveler.");

    public static final PlatformTemplate PLAN = detail("keevo_abonnement",
            "Information sur votre abonnement Keevo :\n{{1}}\n\nPour toute question, contactez le support Keevo.",
            "Votre abonnement Keevo Pro est activé jusqu'au 10/12/2026.");

    public static final PlatformTemplate PLAN_REMINDER_TEMPLATE = detail("keevo_rappel_abonnement",
            "Rappel concernant votre abonnement Keevo :\n{{1}}\n\nPour toute question, contactez le support Keevo.",
            "Votre abonnement Keevo Pro expire le 10/12/2026.");

    public static final PlatformTemplate SUSPENDED = fixed("keevo_compte_suspendu",
            "Votre compte Keevo est suspendu. Contactez le support Keevo.");

    public static final PlatformTemplate REACTIVATED = fixed("keevo_compte_reactive",
            "Votre compte Keevo est réactivé.");

    public static final PlatformTemplate SYNC = utility("keevo_synchronisation",
            "Bonjour, votre boutique Keevo n'a pas été synchronisée depuis {{1}} jours. Ouvrez l'application avec "
                    + "une connexion internet pour sauvegarder vos ventes.", List.of("3"));

    public static final PlatformTemplate MEMBER = utility("keevo_ajout_entreprise",
            "Vous avez rejoint {{1}} en tant que {{2}}. Connectez-vous avec votre compte habituel, puis choisissez "
                    + "cette entreprise.", List.of("Boutique Akwa", "Gérant"));

    public static final PlatformTemplate DELETION = utility("keevo_suppression_programmee",
            "Votre demande de suppression a été enregistrée. Toutes vos données seront supprimées le {{1}}. "
                    + "Pour annuler, accédez à Paramètres > Mon Compte dans l'app.", List.of("10/12/2026"));

    public static final PlatformTemplate DELETED = fixed("keevo_compte_supprime",
            "Votre compte Keevo a été supprimé. Merci d'avoir utilisé Keevo.");

    public static final PlatformTemplate DOCUMENT = new PlatformTemplate("keevo_document_lien", Category.UTILITY, "fr",
            "Bonjour {{1}}, voici votre {{2}} {{3}} de {{4}} d'un montant de {{5}} XAF : {{6}}\n\n"
                    + "Merci pour votre confiance.", "Envoyé avec Keevo",
            List.of("Aminata", "facture", "FAC-2026-000001", "Boutique Mode Akwa", "28 850",
                    "https://keevo.ad2s.cm/d/AbC123"));

    private static final Map<PlatformMessageKind, PlatformTemplate> BY_KIND;
    private static final Map<String, PlatformTemplate> BY_NAME;

    static {
        Map<PlatformMessageKind, PlatformTemplate> byKind = new EnumMap<>(PlatformMessageKind.class);
        byKind.put(DAILY_REPORT, DAILY);
        byKind.put(WEEKLY_REPORT, WEEKLY);
        byKind.put(MULTI_STORE_REPORT, MULTI_STORE);
        byKind.put(DAY_CLOSURE, CLOSURE);
        byKind.put(STOCK_ALERT, STOCK);
        byKind.put(PRODUCTS_CREATED, TEAM);
        byKind.put(STOCK_CHANGED, TEAM);
        byKind.put(PRICES_CHANGED, TEAM);
        byKind.put(SALE_FOLLOW_UP, SALE);
        byKind.put(MODULE_LICENCE, MODULES);
        byKind.put(PLAN_CHANGE, PLAN);
        byKind.put(PlatformMessageKind.PLAN_REMINDER, PLAN_REMINDER_TEMPLATE);
        byKind.put(ACCOUNT_SUSPENDED, SUSPENDED);
        byKind.put(ACCOUNT_REACTIVATED, REACTIVATED);
        byKind.put(SYNC_SILENCE, SYNC);
        byKind.put(MEMBER_ADDED, MEMBER);
        byKind.put(DELETION_SCHEDULED, DELETION);
        byKind.put(ACCOUNT_DELETED, DELETED);
        byKind.put(DOCUMENT_LINK, DOCUMENT);
        BY_KIND = Collections.unmodifiableMap(byKind);

        Map<String, PlatformTemplate> byName = new LinkedHashMap<>();
        byName.put(OTP.name(), OTP);
        byKind.values().forEach(t -> byName.putIfAbsent(t.name(), t));
        BY_NAME = Collections.unmodifiableMap(byName);
    }

    private PlatformTemplateCatalog() {
    }

    /** The template of a kind (every kind has one). */
    public static PlatformTemplate templateFor(PlatformMessageKind kind) {
        return BY_KIND.get(kind);
    }

    /** A template mapped to a kind, by Meta name (the OTP template, sent by {@code sendCode}, is excluded). */
    public static Optional<PlatformTemplate> kindTemplateByName(String name) {
        return byName(name).filter(t -> t != OTP);
    }

    /** A catalogue template by Meta name (OTP included). */
    public static Optional<PlatformTemplate> byName(String name) {
        return Optional.ofNullable(name == null ? null : BY_NAME.get(name));
    }

    /** Every template, OTP first then catalogue order, without duplicates. */
    public static List<PlatformTemplate> all() {
        return List.copyOf(BY_NAME.values());
    }

    /** Kind → template mapping (unmodifiable). */
    public static Map<PlatformMessageKind, PlatformTemplate> mapping() {
        return BY_KIND;
    }

    private static PlatformTemplate detail(String name, String body, String example) {
        return utility(name, body, List.of(example));
    }

    private static PlatformTemplate fixed(String name, String body) {
        return utility(name, body, List.of());
    }

    private static PlatformTemplate utility(String name, String body, List<String> examples) {
        return new PlatformTemplate(name, Category.UTILITY, "fr", body, FOOTER, examples);
    }
}
