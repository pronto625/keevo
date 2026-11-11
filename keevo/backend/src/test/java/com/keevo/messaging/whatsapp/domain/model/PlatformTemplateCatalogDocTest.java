package com.keevo.messaging.whatsapp.domain.model;

import com.keevo.shared.capability.notification.PlatformMessageKind;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static com.keevo.shared.capability.notification.PlatformMessageKind.*;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 19.9c — the deploy document of the platform Meta templates ({@value #DOC}, module base dir) is generated from
 * {@link PlatformTemplateCatalog} and must match it. After changing the catalogue, regenerate it with
 * {@code mvn test -Dtest=PlatformTemplateCatalogDocTest -Dkeevo.whatsapp.templates.regenerate=true} and commit it.
 */
@DisplayName("Platform templates deploy document is up to date (Story 19.9c)")
class PlatformTemplateCatalogDocTest {

    static final String REGENERATE_PROPERTY = "keevo.whatsapp.templates.regenerate";
    static final String DOC = "docs/whatsapp/modeles-meta-plateforme.md";

    /** French description of each kind: message, sending code, variables. */
    record Usage(String message, String sentBy, String variables) {}

    static final Map<PlatformMessageKind, Usage> USAGES = new EnumMap<>(PlatformMessageKind.class);

    static {
        USAGES.put(DAILY_REPORT, new Usage("Rapport du jour d'une boutique (automatique, manuel, renvoi, relance, "
                + "rapport test de l'inscription)", "`AbstractReportGenerator`, `ReportHistoryService`, "
                + "`ReportDeliveryRetryService`, `SendTestReportService`", "{{1}} = texte du rapport"));
        USAGES.put(WEEKLY_REPORT, new Usage("Rapport de la semaine", "`WeeklyReportGenerator` (via "
                + "`AbstractReportGenerator`), renvoi / relance", "{{1}} = texte du rapport"));
        USAGES.put(MULTI_STORE_REPORT, new Usage("Récapitulatif du jour de toutes les boutiques",
                "`MultiStoreSummaryService`, renvoi / relance", "{{1}} = texte du récapitulatif"));
        USAGES.put(DAY_CLOSURE, new Usage("Clôture de caisse", "`DayClosureWhatsAppListener`",
                "{{1}} = texte de la clôture"));
        USAGES.put(STOCK_ALERT, new Usage("Alerte de stock bas (un produit ou plusieurs)",
                "`StockAlertNotificationListener`", "{{1}} = texte de l'alerte"));
        USAGES.put(PRODUCTS_CREATED, new Usage("Produits créés par un employé",
                "`ProductCreationNotificationListener`", "{{1}} = récapitulatif"));
        USAGES.put(STOCK_CHANGED, new Usage("Stock modifié par un employé", "`StockModificationNotificationListener`",
                "{{1}} = récapitulatif"));
        USAGES.put(PRICES_CHANGED, new Usage("Prix modifiés par un employé", "`PriceChangeNotificationListener`",
                "{{1}} = récapitulatif"));
        USAGES.put(SALE_FOLLOW_UP, new Usage("Vente en attente, validée ou complétée",
                "`SaleDraftValidatedNotificationListener`", "{{1}} = titre et texte de la notification"));
        USAGES.put(MODULE_LICENCE, new Usage("Modules arrivés à échéance ou désactivés",
                "`ModuleExpiryNotificationListener`", "{{1}} = texte de l'avis"));
        USAGES.put(PLAN_CHANGE, new Usage("Abonnement activé, repassé au plan Gratuit ou expiré",
                "`PlanWhatsAppNotificationListener`", "{{1}} = texte de l'avis"));
        USAGES.put(PLAN_REMINDER, new Usage("Rappel de fin d'abonnement ou d'essai", "`PlanExpiryReminderService`",
                "{{1}} = texte du rappel"));
        USAGES.put(ACCOUNT_SUSPENDED, new Usage("Compte suspendu par le support",
                "`TenantStatusWhatsAppNotificationListener`", "aucune"));
        USAGES.put(ACCOUNT_REACTIVATED, new Usage("Compte réactivé par le support",
                "`TenantStatusWhatsAppNotificationListener`", "aucune"));
        USAGES.put(SYNC_SILENCE, new Usage("Boutique non synchronisée depuis plusieurs jours",
                "`SyncSilenceAlertService`", "{{1}} = nombre de jours"));
        USAGES.put(MEMBERSHIP_INVITATION, new Usage("Invitation à rejoindre une entreprise", "`MembershipInvitationNotifier`",
                "{{1}} = entreprise, {{2}} = rôle"));
        USAGES.put(DELETION_SCHEDULED, new Usage("Demande de suppression du compte enregistrée",
                "`AccountDeletionService`", "{{1}} = date de suppression"));
        USAGES.put(ACCOUNT_DELETED, new Usage("Compte supprimé", "`TenantDeletionService`", "aucune"));
        USAGES.put(DOCUMENT_LINK, new Usage("Lien d'une facture ou d'un devis envoyé au client depuis le numéro "
                + "Keevo (quand le WhatsApp Business de l'entreprise n'est pas utilisable, 21.5)",
                "`WhatsAppInvoiceChannel`", "{{1}} = client, {{2}} = « facture » ou « devis », {{3}} = référence, "
                + "{{4}} = vendeur, {{5}} = montant, {{6}} = lien"));
    }

    @Test
    @DisplayName("every kind is described in the document")
    void everyKindDescribed() {
        assertThat(USAGES.keySet()).containsExactlyInAnyOrder(PlatformMessageKind.values());
    }

    @Test
    @DisplayName("the checked-in document matches the catalogue")
    void documentUpToDate() throws IOException {
        String expected = render();
        Path doc = Path.of(DOC);
        if (Boolean.getBoolean(REGENERATE_PROPERTY)) {
            Files.createDirectories(doc.getParent());
            Files.writeString(doc, expected, StandardCharsets.UTF_8);
        }
        assertThat(doc).as("%s missing — regenerate with -D%s=true", DOC, REGENERATE_PROPERTY).exists();
        assertThat(Files.readString(doc, StandardCharsets.UTF_8))
                .as("%s is out of date — regenerate with -D%s=true and commit it", DOC, REGENERATE_PROPERTY)
                .isEqualTo(expected);
    }

    static String render() {
        List<PlatformTemplate> toSubmit = PlatformTemplateCatalog.all().stream()
                .filter(t -> t != PlatformTemplateCatalog.OTP).toList();
        StringBuilder md = new StringBuilder();
        md.append("# Modèles WhatsApp Meta de la plateforme Keevo\n\n")
                .append("> **À VALIDER PAR L'UTILISATEUR** — libellés proposés par la story 19.9c, à relire et "
                        + "corriger avant toute soumission à Meta.\n")
                .append("> Document généré depuis le code (`PlatformTemplateCatalog`) : ne pas l'éditer à la main. "
                        + "Après une modification du catalogue : `mvn test -Dtest=PlatformTemplateCatalogDocTest "
                        + "-D" + REGENERATE_PROPERTY + "=true`.\n\n");

        md.append("## 1. Principe\n\n")
                .append("- Ces modèles concernent le **numéro WhatsApp de la plateforme Keevo** (variable "
                        + "`WHATSAPP_PHONE_NUMBER_ID`), pas le WhatsApp Business des entreprises (module 21.5).\n")
                .append("- Avec l'API Meta, un message hors de la fenêtre de 24 h doit utiliser un modèle approuvé. "
                        + "Keevo envoie un message en modèle **seulement** si `KEEVO_WHATSAPP_PROVIDER=meta` et si le "
                        + "nom du modèle figure dans `WHATSAPP_APPROVED_TEMPLATES`.\n")
                .append("- Sinon, et si Meta refuse le modèle, le message part en **texte libre** par Wassender puis "
                        + "Twilio, exactement comme aujourd'hui. Si Meta ne répond pas à temps, rien n'est renvoyé "
                        + "(pas de doublon).\n")
                .append("- Le repli ne couvre que le refus immédiat de Meta : un message accepté par Meta puis non "
                        + "remis (numéro injoignable, modèle suspendu ensuite) n'est pas renvoyé en texte libre. Si un "
                        + "modèle est suspendu ou refusé par Meta, le retirer de `WHATSAPP_APPROVED_TEMPLATES`.\n")
                .append("- Les variables trop longues (rapports) sont mises sur une ligne et coupées par « … » pour "
                        + "respecter la limite Meta de 1 024 caractères ; le détail complet reste dans l'application. "
                        + "Un modèle à plusieurs variables (lien de facture…) n'est jamais coupé : s'il est trop long, "
                        + "le message part en texte libre.\n")
                .append("- Le code de connexion utilise `bref_point_otp` (catégorie Authentification), **déjà "
                        + "approuvé** sur le compte Meta de Bref : ne pas le soumettre à nouveau.\n\n");

        md.append("## 2. Check-list de soumission (Meta Business Manager)\n\n")
                .append("1. Relire et valider les ").append(toSubmit.size())
                .append(" modèles de la section 3 (ou demander leur correction dans le code, puis regénérer ce "
                        + "document).\n")
                .append("2. Ouvrir business.facebook.com › WhatsApp Manager › le compte WhatsApp Business du numéro "
                        + "plateforme › **Modèles de messages** › **Créer un modèle**.\n")
                .append("3. Pour chaque modèle : catégorie **Utilitaire**, nom **exact** (minuscules et `_`), langue "
                        + "**Français (fr)**, aucun en-tête, corps **copié tel quel** (variables `{{1}}`, `{{2}}`…), "
                        + "pied de page indiqué, aucun bouton.\n")
                .append("4. Renseigner un exemple pour chaque variable (colonne « Exemple ») puis **Soumettre**. Ne "
                        + "pas ajouter de formule commerciale (« Profitez… », promotion, réduction) : Meta classerait "
                        + "le modèle en Marketing. Les modèles à une seule variable de détail peuvent être jugés trop "
                        + "génériques par Meta ; en cas de refus, préciser le texte fixe autour de la variable.\n")
                .append("5. Attendre le statut **Approuvé** (de quelques minutes à 48 h). En cas de refus ou de "
                        + "changement de catégorie par Meta (Marketing), noter le motif : le libellé est corrigé dans "
                        + "le code puis resoumis (Meta peut exiger un nouveau nom).\n")
                .append("6. Ajouter les noms approuvés, séparés par des virgules, à `WHATSAPP_APPROVED_TEMPLATES` "
                        + "dans le `.env` de production (ex. `WHATSAPP_APPROVED_TEMPLATES=keevo_alerte_stock,"
                        + "keevo_rapport_journalier`), avec `KEEVO_WHATSAPP_PROVIDER=meta`, puis redémarrer le "
                        + "backend.\n")
                .append("7. Vérifier au démarrage la ligne de log `approved platform templates: [...]` (un nom "
                        + "inconnu est signalé et ignoré).\n")
                .append("8. Déclencher un message réel par modèle approuvé (par exemple le rapport test depuis "
                        + "l'application) et vérifier sa réception sur un téléphone de l'équipe.\n")
                .append("9. Garder Wassender ou Twilio configuré tant que tous les modèles ne sont pas approuvés : "
                        + "c'est le repli des messages non approuvés.\n\n");

        md.append("## 3. Modèles à soumettre (").append(toSubmit.size()).append(")\n\n");
        Map<PlatformTemplate, List<PlatformMessageKind>> kindsByTemplate = PlatformTemplateCatalog.mapping()
                .entrySet().stream().collect(Collectors.groupingBy(Map.Entry::getValue,
                        java.util.LinkedHashMap::new,
                        Collectors.mapping(Map.Entry::getKey, Collectors.toList())));
        int n = 1;
        for (PlatformTemplate t : toSubmit) {
            md.append("### 3.").append(n++).append(" `").append(t.name()).append("`\n\n")
                    .append("- Catégorie : ").append(category(t.category())).append("\n")
                    .append("- Langue : Français (`").append(t.language()).append("`)\n")
                    .append("- Utilisé pour : ").append(kindsByTemplate.getOrDefault(t, List.of()).stream()
                            .map(k -> USAGES.get(k).message()).collect(Collectors.joining(" ; "))).append("\n")
                    .append("- Pied de page : ").append(t.footer() == null ? "aucun" : "« " + t.footer() + " »")
                    .append("\n\nCorps :\n\n```text\n").append(t.body()).append("\n```\n\n");
            if (t.variableCount() == 0) {
                md.append("Aucune variable.\n\n");
            } else {
                md.append("| Variable | Exemple |\n|---|---|\n");
                for (int i = 0; i < t.variableCount(); i++) {
                    md.append("| `{{").append(i + 1).append("}}` | ").append(t.examples().get(i).replace("|", "\\|"))
                            .append(" |\n");
                }
                md.append("\n");
            }
        }

        md.append("## 4. Correspondance message envoyé → modèle\n\n")
                .append("| Message | Envoyé par | Modèle | Variables |\n|---|---|---|---|\n")
                .append("| Code de connexion / réinitialisation | `RequestOtpService`, `RequestPasswordResetService` "
                        + "(`sendCode`) | `").append(PlatformTemplateCatalog.OTP.name())
                .append("` (déjà approuvé, Bref) | {{1}} = code, bouton URL = code |\n");
        for (PlatformMessageKind kind : PlatformMessageKind.values()) {
            Usage u = USAGES.get(kind);
            md.append("| ").append(u.message()).append(" | ").append(u.sentBy()).append(" | `")
                    .append(PlatformTemplateCatalog.templateFor(kind).name()).append("` | ").append(u.variables())
                    .append(" |\n");
        }
        md.append("\nRepli de chaque ligne : texte libre actuel (Wassender puis Twilio) tant que le modèle n'est pas "
                + "listé dans `WHATSAPP_APPROVED_TEMPLATES` ou si Meta le refuse.\n");
        return md.toString();
    }

    private static String category(PlatformTemplate.Category category) {
        return switch (category) {
            case UTILITY -> "Utilitaire (UTILITY)";
            case AUTHENTICATION -> "Authentification (AUTHENTICATION)";
            case MARKETING -> "Marketing (MARKETING)";
        };
    }
}
