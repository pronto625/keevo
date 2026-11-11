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

    public static final PlatformTemplate INVITATION = utility("keevo_invitation_entreprise",
            "Vous êtes invité à rejoindre l'équipe de {{1}} en tant que {{2}}. Ouvrez l'application pour accepter ou "
                    + "refuser. L'invitation expire dans 7 jours. Si vous ne connaissez pas cette entreprise, ignorez ce "
                    + "message.", List.of("Boutique Akwa", "Gérant"));

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

    // ── Story 24.13 — the messages of the Bref cutover (ADR-10), sent by hand through campaigns ──────────────────────
    // Not mapped to a kind and not part of all(): a campaign names its template itself (21.6 in Keevo, the campaigns
    // of the Bref admin before the « go » — same Meta number). One Meta name per message, in French and in English.

    static final String CUTOVER_FOOTER = "Bref Point";

    public static final PlatformTemplate CUTOVER_ANNOUNCE_FR = cutover("bref_bascule_annonce", "fr",
            "Bonjour, Bref Point passe à sa nouvelle version. Le service sera arrêté le {{1}} et reviendra au plus "
                    + "tard le {{2}}.\n\nAvant l'arrêt, ouvrez l'application Bref Point avec une connexion internet "
                    + "pour envoyer vos ventes en attente : une vente restée hors connexion sur un téléphone ne sera "
                    + "pas reprise.\n\nVos boutiques, factures, clients et votre abonnement seront conservés.",
            List.of("samedi 12/12 à 20 h", "dimanche 13/12 à 8 h"));

    public static final PlatformTemplate CUTOVER_ANNOUNCE_EN = cutover("bref_bascule_annonce", "en",
            "Hello, Bref Point is moving to its new version. The service will stop on {{1}} and will be back by "
                    + "{{2}} at the latest.\n\nBefore the stop, open the Bref Point app with an internet connection "
                    + "to send your pending sales: a sale left offline on a phone will not be carried over.\n\n"
                    + "Your shops, invoices, customers and subscription will be kept.",
            List.of("Saturday 12/12 at 8 pm", "Sunday 13/12 at 8 am"));

    public static final PlatformTemplate CUTOVER_REMINDER_FR = cutover("bref_bascule_rappel", "fr",
            "Rappel : Bref Point sera arrêté le {{1}} pour passer à sa nouvelle version. Ouvrez dès maintenant "
                    + "l'application avec une connexion internet pour envoyer vos ventes en attente.\n\nLes ventes "
                    + "enregistrées dans l'application pendant l'arrêt ne seront pas reprises : notez-les sur papier "
                    + "et saisissez-les dans la nouvelle version.",
            List.of("samedi 12/12 à 20 h"));

    public static final PlatformTemplate CUTOVER_REMINDER_EN = cutover("bref_bascule_rappel", "en",
            "Reminder: Bref Point will stop on {{1}} to move to its new version. Open the app now with an internet "
                    + "connection to send your pending sales.\n\nSales recorded in the app during the stop will not "
                    + "be carried over: write them down on paper and enter them in the new version.",
            List.of("Saturday 12/12 at 8 pm"));

    public static final PlatformTemplate CUTOVER_DONE_FR = cutover("bref_bascule_terminee", "fr",
            "Bonjour, la nouvelle version de Bref Point est disponible. Mettez à jour l'application depuis {{1}}, "
                    + "puis connectez-vous avec vos identifiants habituels (un nouveau mot de passe peut vous être "
                    + "demandé).\n\nVos boutiques, factures, clients et votre abonnement ont été repris. Vous pouvez "
                    + "maintenant saisir les ventes notées pendant l'arrêt.",
            List.of("le Play Store ou l'App Store"));

    public static final PlatformTemplate CUTOVER_DONE_EN = cutover("bref_bascule_terminee", "en",
            "Hello, the new version of Bref Point is available. Update the app from {{1}}, then sign in with your "
                    + "usual credentials (you may be asked to choose a new password).\n\nYour shops, invoices, "
                    + "customers and subscription have been carried over. You can now enter the sales written down "
                    + "during the stop.",
            List.of("the Play Store or the App Store"));

    public static final PlatformTemplate CUTOVER_INCIDENT_FR = cutover("bref_bascule_incident", "fr",
            "Bonjour, voici une information sur le passage de Bref Point à sa nouvelle version.\n\n{{1}}\n\n"
                    + "Vos boutiques, factures et clients restent conservés. Nous vous tiendrons informé de la suite par "
                    + "ce même canal. Pour toute question, contactez le support Bref Point au {{2}}.",
            List.of("La mise à jour prend plus de temps que prévu. Le service reviendra au plus tard dimanche 13/12 "
                    + "à 12 h.", "+237 6XX XX XX XX"));

    public static final PlatformTemplate CUTOVER_INCIDENT_EN = cutover("bref_bascule_incident", "en",
            "Hello, here is an update about the move of Bref Point to its new version.\n\n{{1}}\n\n"
                    + "Your shops, invoices and customers remain safe. We will keep you informed through this same "
                    + "channel. For any question, contact Bref Point support at {{2}}.",
            List.of("The update is taking longer than planned. The service will be back by Sunday 13/12 at 12 pm "
                    + "at the latest.", "+237 6XX XX XX XX"));

    private static final List<PlatformTemplate> CUTOVER_CAMPAIGN = List.of(CUTOVER_ANNOUNCE_FR, CUTOVER_ANNOUNCE_EN,
            CUTOVER_REMINDER_FR, CUTOVER_REMINDER_EN, CUTOVER_DONE_FR, CUTOVER_DONE_EN, CUTOVER_INCIDENT_FR,
            CUTOVER_INCIDENT_EN);

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
        byKind.put(MEMBERSHIP_INVITATION, INVITATION);
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

    /**
     * Story 24.13 — the templates of the Bref cutover messages (announcement, reminder, « done », incident), French then
     * English for each name. Used only by campaigns created by hand; never by a kind nor the approved-templates list.
     */
    public static List<PlatformTemplate> campaignTemplates() {
        return CUTOVER_CAMPAIGN;
    }

    /** Kind → template mapping (unmodifiable). */
    public static Map<PlatformMessageKind, PlatformTemplate> mapping() {
        return BY_KIND;
    }

    private static PlatformTemplate cutover(String name, String language, String body, List<String> examples) {
        return new PlatformTemplate(name, Category.UTILITY, language, body, CUTOVER_FOOTER, examples);
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
