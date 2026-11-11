# Modèles WhatsApp Meta de la plateforme Keevo

> **À VALIDER PAR L'UTILISATEUR** — libellés proposés par la story 19.9c, à relire et corriger avant toute soumission à Meta.
> Document généré depuis le code (`PlatformTemplateCatalog`) : ne pas l'éditer à la main. Après une modification du catalogue : `mvn test -Dtest=PlatformTemplateCatalogDocTest -Dkeevo.whatsapp.templates.regenerate=true`.

## 1. Principe

- Ces modèles concernent le **numéro WhatsApp de la plateforme Keevo** (variable `WHATSAPP_PHONE_NUMBER_ID`), pas le WhatsApp Business des entreprises (module 21.5).
- Avec l'API Meta, un message hors de la fenêtre de 24 h doit utiliser un modèle approuvé. Keevo envoie un message en modèle **seulement** si `KEEVO_WHATSAPP_PROVIDER=meta` et si le nom du modèle figure dans `WHATSAPP_APPROVED_TEMPLATES`.
- Sinon, et si Meta refuse le modèle, le message part en **texte libre** par Wassender puis Twilio, exactement comme aujourd'hui. Si Meta ne répond pas à temps, rien n'est renvoyé (pas de doublon).
- Le repli ne couvre que le refus immédiat de Meta : un message accepté par Meta puis non remis (numéro injoignable, modèle suspendu ensuite) n'est pas renvoyé en texte libre. Si un modèle est suspendu ou refusé par Meta, le retirer de `WHATSAPP_APPROVED_TEMPLATES`.
- Les variables trop longues (rapports) sont mises sur une ligne et coupées par « … » pour respecter la limite Meta de 1 024 caractères ; le détail complet reste dans l'application. Un modèle à plusieurs variables (lien de facture…) n'est jamais coupé : s'il est trop long, le message part en texte libre.
- Le code de connexion utilise `bref_point_otp` (catégorie Authentification), **déjà approuvé** sur le compte Meta de Bref : ne pas le soumettre à nouveau.

## 2. Check-list de soumission (Meta Business Manager)

1. Relire et valider les 17 modèles de la section 3 (ou demander leur correction dans le code, puis regénérer ce document).
2. Ouvrir business.facebook.com › WhatsApp Manager › le compte WhatsApp Business du numéro plateforme › **Modèles de messages** › **Créer un modèle**.
3. Pour chaque modèle : catégorie **Utilitaire**, nom **exact** (minuscules et `_`), langue **Français (fr)**, aucun en-tête, corps **copié tel quel** (variables `{{1}}`, `{{2}}`…), pied de page indiqué, aucun bouton.
4. Renseigner un exemple pour chaque variable (colonne « Exemple ») puis **Soumettre**. Ne pas ajouter de formule commerciale (« Profitez… », promotion, réduction) : Meta classerait le modèle en Marketing. Les modèles à une seule variable de détail peuvent être jugés trop génériques par Meta ; en cas de refus, préciser le texte fixe autour de la variable.
5. Attendre le statut **Approuvé** (de quelques minutes à 48 h). En cas de refus ou de changement de catégorie par Meta (Marketing), noter le motif : le libellé est corrigé dans le code puis resoumis (Meta peut exiger un nouveau nom).
6. Ajouter les noms approuvés, séparés par des virgules, à `WHATSAPP_APPROVED_TEMPLATES` dans le `.env` de production (ex. `WHATSAPP_APPROVED_TEMPLATES=keevo_alerte_stock,keevo_rapport_journalier`), avec `KEEVO_WHATSAPP_PROVIDER=meta`, puis redémarrer le backend.
7. Vérifier au démarrage la ligne de log `approved platform templates: [...]` (un nom inconnu est signalé et ignoré).
8. Déclencher un message réel par modèle approuvé (par exemple le rapport test depuis l'application) et vérifier sa réception sur un téléphone de l'équipe.
9. Garder Wassender ou Twilio configuré tant que tous les modèles ne sont pas approuvés : c'est le repli des messages non approuvés.

## 3. Modèles à soumettre (17)

### 3.1 `keevo_rapport_journalier`

- Catégorie : Utilitaire (UTILITY)
- Langue : Français (`fr`)
- Utilisé pour : Rapport du jour d'une boutique (automatique, manuel, renvoi, relance, rapport test de l'inscription)
- Pied de page : « Message automatique de Keevo »

Corps :

```text
Bonjour, voici le rapport de votre journée sur Keevo :
{{1}}

Retrouvez le détail complet dans l'application Keevo.
```

| Variable | Exemple |
|---|---|
| `{{1}}` | Boutique Akwa · Ventes : 12 · Chiffre d'affaires : 85 000 FCFA · Meilleur produit : Riz 5 kg |

### 3.2 `keevo_rapport_hebdomadaire`

- Catégorie : Utilitaire (UTILITY)
- Langue : Français (`fr`)
- Utilisé pour : Rapport de la semaine
- Pied de page : « Message automatique de Keevo »

Corps :

```text
Bonjour, voici le rapport de votre semaine sur Keevo :
{{1}}

Retrouvez le détail complet dans l'application Keevo.
```

| Variable | Exemple |
|---|---|
| `{{1}}` | Semaine du 03/11 au 09/11 · Ventes : 84 · Chiffre d'affaires : 610 000 FCFA |

### 3.3 `keevo_rapport_boutiques`

- Catégorie : Utilitaire (UTILITY)
- Langue : Français (`fr`)
- Utilisé pour : Récapitulatif du jour de toutes les boutiques
- Pied de page : « Message automatique de Keevo »

Corps :

```text
Bonjour, voici le récapitulatif du jour de toutes vos boutiques sur Keevo :
{{1}}

Retrouvez le détail complet dans l'application Keevo.
```

| Variable | Exemple |
|---|---|
| `{{1}}` | Boutique Akwa : 85 000 FCFA · Boutique Bonapriso : 42 500 FCFA · Total : 127 500 FCFA |

### 3.4 `keevo_cloture_caisse`

- Catégorie : Utilitaire (UTILITY)
- Langue : Français (`fr`)
- Utilisé pour : Clôture de caisse
- Pied de page : « Message automatique de Keevo »

Corps :

```text
Une clôture de caisse vient d'être enregistrée sur Keevo :
{{1}}

Retrouvez le détail dans l'application Keevo.
```

| Variable | Exemple |
|---|---|
| `{{1}}` | Boutique Akwa · Clôture de 18:30 · Espèces : 64 000 FCFA · Mobile Money : 21 000 FCFA |

### 3.5 `keevo_alerte_stock`

- Catégorie : Utilitaire (UTILITY)
- Langue : Français (`fr`)
- Utilisé pour : Alerte de stock bas (un produit ou plusieurs)
- Pied de page : « Message automatique de Keevo »

Corps :

```text
Alerte de stock bas sur Keevo :
{{1}}

Pensez à réapprovisionner ces produits.
```

| Variable | Exemple |
|---|---|
| `{{1}}` | Riz 5 kg : 3 unité(s) restante(s) (seuil : 5) — Boutique Akwa |

### 3.6 `keevo_activite_equipe`

- Catégorie : Utilitaire (UTILITY)
- Langue : Français (`fr`)
- Utilisé pour : Produits créés par un employé ; Stock modifié par un employé ; Prix modifiés par un employé
- Pied de page : « Message automatique de Keevo »

Corps :

```text
Nouvelle activité de votre équipe sur Keevo :
{{1}}

Retrouvez le détail dans l'application Keevo.
```

| Variable | Exemple |
|---|---|
| `{{1}}` | Awa a créé 3 produits dans Boutique Akwa : Riz 5 kg, Huile 1 L, Sucre 1 kg |

### 3.7 `keevo_suivi_vente`

- Catégorie : Utilitaire (UTILITY)
- Langue : Français (`fr`)
- Utilisé pour : Vente en attente, validée ou complétée
- Pied de page : « Message automatique de Keevo »

Corps :

```text
Suivi des ventes de votre entreprise sur Keevo :
{{1}}

Retrouvez le détail dans l'application Keevo.
```

| Variable | Exemple |
|---|---|
| `{{1}}` | Vente validée · Awa a validé une vente de 12 500 FCFA à Boutique Akwa |

### 3.8 `keevo_modules`

- Catégorie : Utilitaire (UTILITY)
- Langue : Français (`fr`)
- Utilisé pour : Modules arrivés à échéance ou désactivés
- Pied de page : « Message automatique de Keevo »

Corps :

```text
Information sur les modules de votre compte Keevo :
{{1}}

Pour toute question, contactez le support Keevo.
```

| Variable | Exemple |
|---|---|
| `{{1}}` | Votre module Facturation est arrivé à échéance : il reste consultable en lecture seule jusqu'au 15/11/2026. Contactez-nous pour le renouveler. |

### 3.9 `keevo_abonnement`

- Catégorie : Utilitaire (UTILITY)
- Langue : Français (`fr`)
- Utilisé pour : Abonnement activé, repassé au plan Gratuit ou expiré
- Pied de page : « Message automatique de Keevo »

Corps :

```text
Information sur votre abonnement Keevo :
{{1}}

Pour toute question, contactez le support Keevo.
```

| Variable | Exemple |
|---|---|
| `{{1}}` | Votre abonnement Keevo Pro est activé jusqu'au 10/12/2026. |

### 3.10 `keevo_rappel_abonnement`

- Catégorie : Utilitaire (UTILITY)
- Langue : Français (`fr`)
- Utilisé pour : Rappel de fin d'abonnement ou d'essai
- Pied de page : « Message automatique de Keevo »

Corps :

```text
Rappel concernant votre abonnement Keevo :
{{1}}

Pour toute question, contactez le support Keevo.
```

| Variable | Exemple |
|---|---|
| `{{1}}` | Votre abonnement Keevo Pro expire le 10/12/2026. |

### 3.11 `keevo_compte_suspendu`

- Catégorie : Utilitaire (UTILITY)
- Langue : Français (`fr`)
- Utilisé pour : Compte suspendu par le support
- Pied de page : « Message automatique de Keevo »

Corps :

```text
Votre compte Keevo est suspendu. Contactez le support Keevo.
```

Aucune variable.

### 3.12 `keevo_compte_reactive`

- Catégorie : Utilitaire (UTILITY)
- Langue : Français (`fr`)
- Utilisé pour : Compte réactivé par le support
- Pied de page : « Message automatique de Keevo »

Corps :

```text
Votre compte Keevo est réactivé.
```

Aucune variable.

### 3.13 `keevo_synchronisation`

- Catégorie : Utilitaire (UTILITY)
- Langue : Français (`fr`)
- Utilisé pour : Boutique non synchronisée depuis plusieurs jours
- Pied de page : « Message automatique de Keevo »

Corps :

```text
Bonjour, votre boutique Keevo n'a pas été synchronisée depuis {{1}} jours. Ouvrez l'application avec une connexion internet pour sauvegarder vos ventes.
```

| Variable | Exemple |
|---|---|
| `{{1}}` | 3 |

### 3.14 `keevo_invitation_entreprise`

- Catégorie : Utilitaire (UTILITY)
- Langue : Français (`fr`)
- Utilisé pour : Invitation à rejoindre une entreprise
- Pied de page : « Message automatique de Keevo »

Corps :

```text
Vous êtes invité à rejoindre l'équipe de {{1}} en tant que {{2}}. Ouvrez l'application pour accepter ou refuser. L'invitation expire dans 7 jours. Si vous ne connaissez pas cette entreprise, ignorez ce message.
```

| Variable | Exemple |
|---|---|
| `{{1}}` | Boutique Akwa |
| `{{2}}` | Gérant |

### 3.15 `keevo_suppression_programmee`

- Catégorie : Utilitaire (UTILITY)
- Langue : Français (`fr`)
- Utilisé pour : Demande de suppression du compte enregistrée
- Pied de page : « Message automatique de Keevo »

Corps :

```text
Votre demande de suppression a été enregistrée. Toutes vos données seront supprimées le {{1}}. Pour annuler, accédez à Paramètres > Mon Compte dans l'app.
```

| Variable | Exemple |
|---|---|
| `{{1}}` | 10/12/2026 |

### 3.16 `keevo_compte_supprime`

- Catégorie : Utilitaire (UTILITY)
- Langue : Français (`fr`)
- Utilisé pour : Compte supprimé
- Pied de page : « Message automatique de Keevo »

Corps :

```text
Votre compte Keevo a été supprimé. Merci d'avoir utilisé Keevo.
```

Aucune variable.

### 3.17 `keevo_document_lien`

- Catégorie : Utilitaire (UTILITY)
- Langue : Français (`fr`)
- Utilisé pour : Lien d'une facture ou d'un devis envoyé au client depuis le numéro Keevo (quand le WhatsApp Business de l'entreprise n'est pas utilisable, 21.5)
- Pied de page : « Envoyé avec Keevo »

Corps :

```text
Bonjour {{1}}, voici votre {{2}} {{3}} de {{4}} d'un montant de {{5}} XAF : {{6}}

Merci pour votre confiance.
```

| Variable | Exemple |
|---|---|
| `{{1}}` | Aminata |
| `{{2}}` | facture |
| `{{3}}` | FAC-2026-000001 |
| `{{4}}` | Boutique Mode Akwa |
| `{{5}}` | 28 850 |
| `{{6}}` | https://keevo.ad2s.cm/d/AbC123 |

## 4. Correspondance message envoyé → modèle

| Message | Envoyé par | Modèle | Variables |
|---|---|---|---|
| Code de connexion / réinitialisation | `RequestOtpService`, `RequestPasswordResetService` (`sendCode`) | `bref_point_otp` (déjà approuvé, Bref) | {{1}} = code, bouton URL = code |
| Rapport du jour d'une boutique (automatique, manuel, renvoi, relance, rapport test de l'inscription) | `AbstractReportGenerator`, `ReportHistoryService`, `ReportDeliveryRetryService`, `SendTestReportService` | `keevo_rapport_journalier` | {{1}} = texte du rapport |
| Rapport de la semaine | `WeeklyReportGenerator` (via `AbstractReportGenerator`), renvoi / relance | `keevo_rapport_hebdomadaire` | {{1}} = texte du rapport |
| Récapitulatif du jour de toutes les boutiques | `MultiStoreSummaryService`, renvoi / relance | `keevo_rapport_boutiques` | {{1}} = texte du récapitulatif |
| Clôture de caisse | `DayClosureWhatsAppListener` | `keevo_cloture_caisse` | {{1}} = texte de la clôture |
| Alerte de stock bas (un produit ou plusieurs) | `StockAlertNotificationListener` | `keevo_alerte_stock` | {{1}} = texte de l'alerte |
| Produits créés par un employé | `ProductCreationNotificationListener` | `keevo_activite_equipe` | {{1}} = récapitulatif |
| Stock modifié par un employé | `StockModificationNotificationListener` | `keevo_activite_equipe` | {{1}} = récapitulatif |
| Prix modifiés par un employé | `PriceChangeNotificationListener` | `keevo_activite_equipe` | {{1}} = récapitulatif |
| Vente en attente, validée ou complétée | `SaleDraftValidatedNotificationListener` | `keevo_suivi_vente` | {{1}} = titre et texte de la notification |
| Modules arrivés à échéance ou désactivés | `ModuleExpiryNotificationListener` | `keevo_modules` | {{1}} = texte de l'avis |
| Abonnement activé, repassé au plan Gratuit ou expiré | `PlanWhatsAppNotificationListener` | `keevo_abonnement` | {{1}} = texte de l'avis |
| Rappel de fin d'abonnement ou d'essai | `PlanExpiryReminderService` | `keevo_rappel_abonnement` | {{1}} = texte du rappel |
| Compte suspendu par le support | `TenantStatusWhatsAppNotificationListener` | `keevo_compte_suspendu` | aucune |
| Compte réactivé par le support | `TenantStatusWhatsAppNotificationListener` | `keevo_compte_reactive` | aucune |
| Boutique non synchronisée depuis plusieurs jours | `SyncSilenceAlertService` | `keevo_synchronisation` | {{1}} = nombre de jours |
| Invitation à rejoindre une entreprise | `MembershipInvitationNotifier` | `keevo_invitation_entreprise` | {{1}} = entreprise, {{2}} = rôle |
| Demande de suppression du compte enregistrée | `AccountDeletionService` | `keevo_suppression_programmee` | {{1}} = date de suppression |
| Compte supprimé | `TenantDeletionService` | `keevo_compte_supprime` | aucune |
| Lien d'une facture ou d'un devis envoyé au client depuis le numéro Keevo (quand le WhatsApp Business de l'entreprise n'est pas utilisable, 21.5) | `WhatsAppInvoiceChannel` | `keevo_document_lien` | {{1}} = client, {{2}} = « facture » ou « devis », {{3}} = référence, {{4}} = vendeur, {{5}} = montant, {{6}} = lien |

Repli de chaque ligne : texte libre actuel (Wassender puis Twilio) tant que le modèle n'est pas listé dans `WHATSAPP_APPROVED_TEMPLATES` ou si Meta le refuse.
