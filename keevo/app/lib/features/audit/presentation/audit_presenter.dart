import 'package:flutter/material.dart' show IconData, Icons;

import '../domain/audit_format.dart';
import '../domain/audit_labels.dart';
import '../domain/model/audit_entry_dto.dart';

/// Families of actions — drive the icon color and the « Action » filter order.
enum AuditCategory {
  product,
  price,
  stock,
  sale,
  contact,
  catalog,
  store,
  team,
  account,
  closure,
  inventory,
  other,
}

/// How one action code reads in the history (v1s-18-7).
class AuditActionInfo {
  /// Short title (« Produit créé »), used by the filter and the detail.
  final String label;

  /// Past participle phrase after the auxiliary (« créé le produit »).
  final String phrase;
  final IconData icon;
  final AuditCategory category;

  /// Whether the object label is appended to the sentence.
  final bool takesObject;

  /// Object used when the entry has no readable label (« un employé »).
  final String? fallbackObject;

  /// Pronominal verb (« s'est connecté(e) »).
  final bool reflexive;

  const AuditActionInfo(
    this.label,
    this.phrase,
    this.icon,
    this.category, {
    this.takesObject = true,
    this.fallbackObject,
    this.reflexive = false,
  });
}

/// A sentence split so the page can bold the actor and the object.
class AuditSentence {
  final String actor;
  final String verb;
  final String? object;

  /// Text between [verb] and [object]: a space, or nothing after an elided
  /// « d' » (« le rôle d'un employé »).
  final String joiner;

  const AuditSentence(this.actor, this.verb, this.object, {this.joiner = ' '});

  @override
  String toString() =>
      object == null ? '$actor $verb' : '$actor $verb$joiner$object';
}

/// One line of the « Changements » table.
class AuditChange {
  final String field;
  final String before;
  final String after;

  const AuditChange(this.field, this.before, this.after);

  @override
  bool operator ==(Object other) =>
      other is AuditChange &&
      other.field == field &&
      other.before == before &&
      other.after == after;

  @override
  int get hashCode => Object.hash(field, before, after);

  @override
  String toString() => 'AuditChange($field: $before → $after)';
}

/// One line of the « Contexte » section.
class AuditContextItem {
  final String label;
  final String value;

  const AuditContextItem(this.label, this.value);

  @override
  bool operator ==(Object other) =>
      other is AuditContextItem && other.label == label && other.value == value;

  @override
  int get hashCode => Object.hash(label, value);

  @override
  String toString() => 'AuditContextItem($label: $value)';
}

/// AuditPresenter — pure rendering rules of the « Historique des actions »
/// list and detail (v1s-18-7). Never throws: unknown actions, fields and
/// malformed JSON fall back to a generic rendering.
///
/// v1s-16-20 — no technical data: labels come from the shared table
/// (`audit_labels.dart`, pinned to the server `AuditLabels` by the export
/// contract test) and values are rendered by [AuditFormat], the mirror of
/// the server export formatter: no id, raw code, device id or JSON.
class AuditPresenter {
  const AuditPresenter._();

  static const _product = 'un produit';
  static const _employee = 'un employé';

  /// Every action written by the backend `AuditEventListener`.
  static const Map<String, AuditActionInfo> catalogue = {
    // Account / identity
    'USER_REGISTERED': AuditActionInfo('Inscription', 'créé son compte',
        Icons.person_add_alt_1_outlined, AuditCategory.account,
        takesObject: false),
    'USER_AUTHENTICATED': AuditActionInfo(
        'Connexion', 'connecté(e)', Icons.login_rounded, AuditCategory.account,
        takesObject: false, reflexive: true),
    'PASSWORD_RESET_REQUESTED': AuditActionInfo(
        'Réinitialisation demandée',
        'demandé la réinitialisation de son mot de passe',
        Icons.lock_reset_rounded,
        AuditCategory.account,
        takesObject: false),
    'PASSWORD_RESET': AuditActionInfo(
        'Mot de passe réinitialisé',
        'réinitialisé son mot de passe',
        Icons.lock_reset_rounded,
        AuditCategory.account,
        takesObject: false),
    'ONBOARDING_COMPLETED': AuditActionInfo(
        'Configuration terminée',
        'terminé la configuration de',
        Icons.rocket_launch_outlined,
        AuditCategory.account,
        fallbackObject: 'la boutique'),
    'ACCOUNT_DELETION_REQUESTED': AuditActionInfo(
        'Suppression du compte demandée',
        'demandé la suppression du compte',
        Icons.no_accounts_outlined,
        AuditCategory.account,
        takesObject: false),
    'ACCOUNT_DELETION_CANCELLED': AuditActionInfo(
        'Suppression du compte annulée',
        'annulé la suppression du compte',
        Icons.manage_accounts_outlined,
        AuditCategory.account,
        takesObject: false),
    'REPORT_PREFERENCES_UPDATED': AuditActionInfo(
        'Préférences des rapports modifiées',
        'modifié les préférences des rapports',
        Icons.tune_rounded,
        AuditCategory.account,
        takesObject: false),
    // Products
    'PRODUCT_CREATED': AuditActionInfo('Produit créé', 'créé le produit',
        Icons.inventory_2_outlined, AuditCategory.product),
    'PRODUCT_UPDATED': AuditActionInfo('Produit modifié', 'modifié le produit',
        Icons.edit_outlined, AuditCategory.product),
    'PRODUCT_PHOTO_UPDATED': AuditActionInfo('Photo du produit modifiée',
        'modifié la photo du produit', Icons.photo_camera_outlined,
        AuditCategory.product),
    'PRODUCT_ARCHIVED': AuditActionInfo('Produit archivé', 'archivé le produit',
        Icons.archive_outlined, AuditCategory.product),
    'PRODUCT_ACTIVATED': AuditActionInfo('Produit activé', 'activé le produit',
        Icons.check_circle_outline_rounded, AuditCategory.product),
    'PRODUCT_UNARCHIVED': AuditActionInfo('Produit désarchivé',
        'désarchivé le produit', Icons.unarchive_outlined, AuditCategory.product),
    'PRICE_OVERRIDDEN': AuditActionInfo('Prix modifié en caisse',
        'modifié le prix de', Icons.sell_outlined, AuditCategory.price,
        fallbackObject: _product),
    // Stock
    'STOCK_ADJUSTED': AuditActionInfo('Mouvement de stock',
        'ajusté le stock de', Icons.swap_vert_rounded, AuditCategory.stock,
        fallbackObject: _product),
    'STOCK_THRESHOLD_UPDATED': AuditActionInfo(
        'Seuil d\'alerte modifié',
        'modifié le seuil d\'alerte de',
        Icons.tune_rounded,
        AuditCategory.stock,
        fallbackObject: _product),
    'STOCK_THRESHOLD_BREACHED': AuditActionInfo(
        'Alerte de stock bas',
        'déclenché une alerte de stock bas sur',
        Icons.warning_amber_rounded,
        AuditCategory.stock,
        fallbackObject: _product),
    'STOCK_FORCED_ZERO': AuditActionInfo(
        'Stock forcé à zéro',
        'forcé à zéro le stock de',
        Icons.exposure_zero_rounded,
        AuditCategory.stock,
        fallbackObject: _product),
    // Contacts
    'CLIENT_CREATED': AuditActionInfo('Client créé', 'créé le client',
        Icons.person_add_alt_outlined, AuditCategory.contact),
    'CLIENT_ARCHIVED': AuditActionInfo('Client archivé', 'archivé le client',
        Icons.person_off_outlined, AuditCategory.contact),
    'CLIENT_UNARCHIVED': AuditActionInfo('Client désarchivé',
        'désarchivé le client', Icons.person_outline, AuditCategory.contact),
    'SUPPLIER_CREATED': AuditActionInfo(
        'Fournisseur créé',
        'créé le fournisseur',
        Icons.local_shipping_outlined,
        AuditCategory.contact),
    'SUPPLIER_ARCHIVED': AuditActionInfo(
        'Fournisseur archivé',
        'archivé le fournisseur',
        Icons.local_shipping_outlined,
        AuditCategory.contact),
    'SUPPLIER_UPDATED': AuditActionInfo(
        'Fournisseur modifié',
        'modifié le fournisseur',
        Icons.local_shipping_outlined,
        AuditCategory.contact),
    'SUPPLIER_PRODUCTS_UPDATED': AuditActionInfo(
        'Produits du fournisseur modifiés',
        'modifié les produits du fournisseur',
        Icons.local_shipping_outlined,
        AuditCategory.contact),
    'SUPPLIER_UNARCHIVED': AuditActionInfo(
        'Fournisseur désarchivé',
        'désarchivé le fournisseur',
        Icons.local_shipping_outlined,
        AuditCategory.contact),
    // Categories
    'CATEGORY_CREATED': AuditActionInfo('Catégorie créée', 'créé la catégorie',
        Icons.category_outlined, AuditCategory.catalog),
    'CATEGORY_RENAMED': AuditActionInfo(
        'Catégorie renommée',
        'renommé la catégorie',
        Icons.drive_file_rename_outline_rounded,
        AuditCategory.catalog),
    'CATEGORY_ACTIVATED': AuditActionInfo('Catégorie activée',
        'activé la catégorie', Icons.category_outlined, AuditCategory.catalog),
    'CATEGORY_DEACTIVATED': AuditActionInfo(
        'Catégorie désactivée',
        'désactivé la catégorie',
        Icons.category_outlined,
        AuditCategory.catalog),
    // Stores
    'STORE_CREATED': AuditActionInfo('Boutique créée', 'créé la boutique',
        Icons.add_business_outlined, AuditCategory.store),
    'STORE_UPDATED': AuditActionInfo('Boutique modifiée', 'modifié la boutique',
        Icons.storefront_outlined, AuditCategory.store),
    'STORE_DEACTIVATED': AuditActionInfo(
        'Boutique désactivée',
        'désactivé la boutique',
        Icons.store_mall_directory_outlined,
        AuditCategory.store),
    'STORE_REACTIVATED': AuditActionInfo('Boutique réactivée',
        'réactivé la boutique', Icons.storefront_outlined, AuditCategory.store),
    // Team
    'EMPLOYEE_CREATED': AuditActionInfo('Employé ajouté', 'ajouté l\'employé',
        Icons.badge_outlined, AuditCategory.team,
        fallbackObject: _employee),
    'EMPLOYEE_UPDATED': AuditActionInfo('Employé modifié',
        'modifié la fiche de', Icons.badge_outlined, AuditCategory.team,
        fallbackObject: _employee),
    'EMPLOYEE_ROLE_CHANGED': AuditActionInfo(
        'Rôle modifié',
        'changé le rôle de',
        Icons.admin_panel_settings_outlined,
        AuditCategory.team,
        fallbackObject: _employee),
    'EMPLOYEE_PASSWORD_SET_BY_OWNER': AuditActionInfo(
        'Mot de passe défini',
        'défini un nouveau mot de passe pour',
        Icons.password_rounded,
        AuditCategory.team,
        fallbackObject: _employee),
    // Sales
    'SALE_COMPLETED': AuditActionInfo('Vente enregistrée', 'enregistré une',
        Icons.point_of_sale_rounded, AuditCategory.sale),
    'SALE_PENDING_VALIDATION': AuditActionInfo(
        'Vente en attente',
        'mis en attente de validation une',
        Icons.hourglass_top_rounded,
        AuditCategory.sale),
    'SALE_AUTO_VALIDATED': AuditActionInfo(
        'Vente validée automatiquement',
        'validé automatiquement une',
        Icons.task_alt_rounded,
        AuditCategory.sale),
    'SALE_MANUALLY_VALIDATED': AuditActionInfo('Vente validée', 'validé une',
        Icons.task_alt_rounded, AuditCategory.sale),
    'SALE_CANCELLED': AuditActionInfo('Vente annulée', 'annulé une',
        Icons.cancel_outlined, AuditCategory.sale),
    'SALE_CORRECTED': AuditActionInfo('Vente corrigée', 'corrigé une',
        Icons.edit_note_rounded, AuditCategory.sale),
    // Closures / inventory
    'DAY_CLOSED': AuditActionInfo('Journée clôturée', 'clôturé la journée',
        Icons.lock_clock_outlined, AuditCategory.closure,
        takesObject: false),
    'INVENTORY_SESSION_CREATED': AuditActionInfo(
        'Inventaire lancé',
        'lancé un inventaire',
        Icons.fact_check_outlined,
        AuditCategory.inventory,
        takesObject: false),
    'INVENTORY_SESSION_CANCELLED': AuditActionInfo(
        'Inventaire annulé',
        'annulé un inventaire',
        Icons.fact_check_outlined,
        AuditCategory.inventory,
        takesObject: false),
    'INVENTORY_COUNT_SAVED': AuditActionInfo(
        'Comptage enregistré',
        'enregistré un comptage d\'inventaire',
        Icons.checklist_rounded,
        AuditCategory.inventory,
        takesObject: false),
    'INVENTORY_REPORT_GENERATED': AuditActionInfo(
        'Rapport d\'inventaire',
        'généré le rapport d\'inventaire',
        Icons.summarize_outlined,
        AuditCategory.inventory,
        takesObject: false),
    'INVENTORY_VALIDATED': AuditActionInfo(
        'Inventaire validé',
        'validé un inventaire',
        Icons.verified_outlined,
        AuditCategory.inventory,
        takesObject: false),
    // Export (v1s-18-1)
    'DATA_EXPORTED': AuditActionInfo('Données exportées', 'exporté des données',
        Icons.file_download_outlined, AuditCategory.other,
        takesObject: false),
  };

  /// Code → title of every catalogue action; equals the contract
  /// `labels["audit_log.action"]` (server `AuditLabels.ACTIONS`).
  static Map<String, String> get catalogueLabels =>
      {for (final e in catalogue.entries) e.key: e.value.label};

  /// Object types offered by the « Objet » filter (`entityType` values).
  static const Map<String, String> objectTypes = {
    'Product': 'Produits',
    'Sale': 'Ventes',
    'StockMovement': 'Mouvements de stock',
    'PriceOverride': 'Prix modifiés',
    'Client': 'Clients',
    'Supplier': 'Fournisseurs',
    'Category': 'Catégories',
    'Store': 'Boutiques',
    'Employee': 'Employés',
    'DayClosure': 'Clôtures de journée',
    'InventorySession': 'Inventaires',
    'InventoryCount': 'Comptages d\'inventaire',
    'StockLevel': 'Niveaux de stock',
    'StockAlert': 'Alertes de stock',
    'Tenant': 'Compte',
    'User': 'Utilisateurs',
    'Export': 'Exports',
  };

  /// Singular noun of each object type (contract `audit_log.entityType`).
  static const typeNouns = auditEntityTypeLabels;

  /// « Objet » filter label of [entityType]: the plural label, else the
  /// humanized type (never the raw class-like code).
  static String objectTypeLabel(String entityType) =>
      objectTypes[entityType] ?? humanize(entityType);

  // ── Action ────────────────────────────────────────────────────────────────

  /// Catalogue entry, or a generic one for an unknown code.
  static AuditActionInfo actionInfo(String action) =>
      catalogue[action] ??
      AuditActionInfo(humanize(action), 'effectué « ${humanize(action)} »',
          Icons.history_rounded, AuditCategory.other);

  static bool isKnownAction(String action) => catalogue.containsKey(action);

  /// `FOO_BAR` → `Foo bar`, `someKey` → `Some key`.
  static String humanize(String code) => AuditFormat.humanize(code);

  // ── Actor ─────────────────────────────────────────────────────────────────

  static bool isSelf(AuditEntryDto e, String? selfUserId) =>
      selfUserId != null && e.userId == selfUserId;

  /// Display name of the actor (never a UUID).
  static String actorName(AuditEntryDto e, {String? selfUserId}) {
    if (isSelf(e, selfUserId)) return 'Vous';
    final name = e.actorName?.trim();
    if (name != null && name.isNotEmpty) return name;
    final label = _string(AuditFormat.decodeMap(e.metadata)['actorLabel']);
    if (label != null) return label;
    final phone = e.actorPhone?.trim();
    if (phone != null && phone.isNotEmpty) return phone;
    return 'Un utilisateur';
  }

  // ── Sentence ──────────────────────────────────────────────────────────────

  /// « **Awa** a créé le produit **Pagne wax** » / « **Vous** avez … ».
  static AuditSentence sentence(AuditEntryDto e, {String? selfUserId}) {
    final self = isSelf(e, selfUserId);
    final info = actionInfo(e.action);
    final actor = actorName(e, selfUserId: selfUserId);
    final aux = info.reflexive
        ? (self ? 'vous êtes' : 's\'est')
        : (self ? 'avez' : 'a');

    String? object;
    var phrase = e.action == 'STOCK_ADJUSTED'
        ? stockMovementPhrase(e) ?? info.phrase
        : info.phrase;
    if (!isKnownAction(e.action)) {
      final label = _readableLabel(e);
      if (label != null) {
        phrase = '$phrase sur';
        object = label;
      }
    } else if (info.takesObject) {
      if (e.entityType == 'Sale') {
        final total = saleTotal(e);
        object = total != null ? 'vente de ${formatXaf(total)}' : 'vente';
      } else {
        object = _readableLabel(e) ?? info.fallbackObject;
      }
    }
    // French elision: « de un employé » → « d'un employé ».
    if (object != null &&
        phrase.endsWith(' de') &&
        RegExp(r'^[aeiouyhàâéèêëîïôûü]', caseSensitive: false)
            .hasMatch(object)) {
      return AuditSentence(
          actor, '$aux ${phrase.substring(0, phrase.length - 3)} d\'', object,
          joiner: '');
    }
    return AuditSentence(actor, '$aux $phrase', object);
  }

  /// A38: a stock movement reads after what caused it (`value_after
  /// .movementType`): the decrement of a sale is « a vendu X », not « a ajusté
  /// le stock de X ». Null for a manual adjustment or an unknown type (the
  /// catalogue phrase « ajusté le stock de » applies).
  static String? stockMovementPhrase(AuditEntryDto e) =>
      switch (AuditFormat.decodeMap(e.valueAfter)['movementType']) {
        'SALE' => 'vendu',
        'SALE_CANCELLED' => 'remis en stock',
        'STOCK_ENTRY' || 'PURCHASE' => 'réapprovisionné',
        'TRANSFER_OUT' => 'transféré du stock de',
        'TRANSFER_IN' => 'reçu par transfert du stock de',
        _ => null,
      };

  // ── Object ────────────────────────────────────────────────────────────────

  /// « Sur quoi »: label at action time, sale amount, else the type noun.
  static String objectLabel(AuditEntryDto e) {
    if (e.entityType == 'Sale') {
      final total = saleTotal(e);
      if (total != null) return 'Vente de ${formatXaf(total)}';
    }
    return _readableLabel(e) ?? AuditFormat.entityTypeLabel(e.entityType);
  }

  /// Sale total from `value_after.totalAmount` (also on legacy rows).
  static num? saleTotal(AuditEntryDto e) {
    final v = AuditFormat.decodeMap(e.valueAfter)['totalAmount'];
    return v is num ? v : null;
  }

  static String? _readableLabel(AuditEntryDto e) {
    final label = e.entityLabel?.trim();
    if (label != null &&
        label.isNotEmpty &&
        !AuditFormat.isTechnical(label)) {
      return label;
    }
    // Legacy rows (before v1s-18-6): the name sits in value_after.
    final after = AuditFormat.decodeMap(e.valueAfter);
    for (final k in const ['productName', 'name', 'newName', 'storeName']) {
      final s = _string(after[k]);
      if (s != null) return s;
    }
    return null;
  }

  // ── Changes ───────────────────────────────────────────────────────────────

  /// Field-by-field « Avant → Après » rows (server export rules: hidden ids,
  /// no UUID value, no nested object, translated codes). Dates in local time.
  static List<AuditChange> changes(AuditEntryDto e) => [
        for (final l in AuditFormat.changes(e.valueBefore, e.valueAfter,
            display: _local))
          AuditChange(l.field, l.before ?? AuditFormat.none, l.after),
      ];

  static String fieldLabel(String key) => AuditFormat.fieldLabel(key);

  static bool isAmountKey(String k) => AuditFormat.isAmountKey(k);

  /// Formats one value: XAF amounts, units, translated codes, dates, lists
  /// (« — » when the value must not be shown).
  static String formatValue(String key, Object? v) =>
      AuditFormat.formatValue(key, v, display: _local) ?? AuditFormat.none;

  static DateTime _local(DateTime d) => d.toLocal();

  // ── Context ───────────────────────────────────────────────────────────────

  /// « Contexte » rows from `metadata` (or, for legacy rows, the context keys
  /// still in `value_after`). Empty when there is nothing to show.
  static List<AuditContextItem> context(AuditEntryDto e) => [
        for (final r
            in AuditFormat.details(e.metadata, e.valueAfter, display: _local))
          AuditContextItem(r.$1, r.$2),
      ];

  // ── Where / how ───────────────────────────────────────────────────────────

  static String sourceLabel(AuditEntryDto e) {
    switch (e.source) {
      case 'ONLINE':
        return 'En ligne';
      case 'SYNC':
        final hasDevice = e.deviceId?.trim().isNotEmpty ?? false;
        return hasDevice
            ? 'Synchronisé depuis l\'appareil de ${_deviceOwner(e)}'
            : 'Synchronisé depuis l\'appareil';
      case 'SYSTEM':
        return 'Automatique (système)';
      case null:
        return 'Non renseigné';
      default:
        return humanize(e.source!);
    }
  }

  /// « Appareil de Awa » — a device id has no readable name, so the device
  /// is named after the actor; `null` (row omitted) without device.
  static String? deviceLabel(AuditEntryDto e) {
    final d = e.deviceId?.trim();
    if (d == null || d.isEmpty) return null;
    return 'Appareil de ${_deviceOwner(e)}';
  }

  static String _deviceOwner(AuditEntryDto e) {
    final name = actorName(e);
    return name == 'Un utilisateur' ? 'l\'auteur' : name;
  }

  // ── Formatting ────────────────────────────────────────────────────────────

  /// `15 000 XAF` (integer XAF, space grouping).
  static String formatXaf(num amount) => AuditFormat.formatXaf(amount);

  /// `dd/MM/yyyy HH:mm` in local time.
  static String formatDateTime(DateTime d) {
    final l = d.toLocal();
    return '${_pad2(l.day)}/${_pad2(l.month)}/${l.year} ${_pad2(l.hour)}:${_pad2(l.minute)}';
  }

  /// `HH:mm` in local time.
  static String formatTime(DateTime d) {
    final l = d.toLocal();
    return '${_pad2(l.hour)}:${_pad2(l.minute)}';
  }

  static const _months = [
    'janvier',
    'février',
    'mars',
    'avril',
    'mai',
    'juin',
    'juillet',
    'août',
    'septembre',
    'octobre',
    'novembre',
    'décembre',
  ];

  /// « Aujourd'hui », « Hier », else `dd MMMM yyyy` (French).
  static String dayHeader(DateTime d, {DateTime? now}) {
    final l = d.toLocal();
    final n = (now ?? DateTime.now()).toLocal();
    final day = DateTime(l.year, l.month, l.day);
    final today = DateTime(n.year, n.month, n.day);
    if (day == today) return 'Aujourd\'hui';
    if (day == DateTime(today.year, today.month, today.day - 1)) return 'Hier';
    return '${_pad2(l.day)} ${_months[l.month - 1]} ${l.year}';
  }

  static String _pad2(int v) => v.toString().padLeft(2, '0');

  // ── Helpers ───────────────────────────────────────────────────────────────

  static String? _string(Object? v) {
    if (v is! String) return null;
    final s = v.trim();
    return s.isEmpty ? null : s;
  }
}
