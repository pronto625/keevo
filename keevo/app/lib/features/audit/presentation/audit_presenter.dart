import 'dart:convert';

import 'package:flutter/material.dart' show IconData, Icons;

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
    // Products
    'PRODUCT_CREATED': AuditActionInfo('Produit créé', 'créé le produit',
        Icons.inventory_2_outlined, AuditCategory.product),
    'PRODUCT_UPDATED': AuditActionInfo('Produit modifié', 'modifié le produit',
        Icons.edit_outlined, AuditCategory.product),
    'PRODUCT_ARCHIVED': AuditActionInfo('Produit archivé', 'archivé le produit',
        Icons.archive_outlined, AuditCategory.product),
    'PRODUCT_ACTIVATED': AuditActionInfo('Produit activé', 'activé le produit',
        Icons.check_circle_outline_rounded, AuditCategory.product),
    'PRICE_OVERRIDDEN': AuditActionInfo('Prix modifié en caisse',
        'modifié le prix de', Icons.sell_outlined, AuditCategory.price,
        fallbackObject: _product),
    // Stock
    'STOCK_ADJUSTED': AuditActionInfo('Mouvement de stock',
        'ajusté le stock de', Icons.swap_vert_rounded, AuditCategory.stock,
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
  };

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
  };

  static const _typeNouns = {
    'Product': 'Produit',
    'Sale': 'Vente',
    'StockMovement': 'Mouvement de stock',
    'StockAlert': 'Alerte de stock',
    'StockLevel': 'Niveau de stock',
    'PriceOverride': 'Prix de vente',
    'Client': 'Client',
    'Supplier': 'Fournisseur',
    'Category': 'Catégorie',
    'Store': 'Boutique',
    'Employee': 'Un employé',
    'User': 'Compte utilisateur',
    'Tenant': 'Compte',
    'DayClosure': 'Clôture de journée',
    'InventorySession': 'Inventaire',
    'InventoryCount': 'Comptage d\'inventaire',
  };

  // ── Action ────────────────────────────────────────────────────────────────

  /// Catalogue entry, or a generic one for an unknown code.
  static AuditActionInfo actionInfo(String action) =>
      catalogue[action] ??
      AuditActionInfo(humanize(action), 'effectué « ${humanize(action)} »',
          Icons.history_rounded, AuditCategory.other);

  static bool isKnownAction(String action) => catalogue.containsKey(action);

  /// `FOO_BAR` → `Foo bar`, `someKey` → `Some key`.
  static String humanize(String code) {
    final spaced = code
        .replaceAllMapped(
            RegExp(r'([a-z0-9])([A-Z])'), (m) => '${m[1]} ${m[2]}')
        .replaceAll('_', ' ')
        .trim()
        .toLowerCase();
    if (spaced.isEmpty) return code;
    return spaced[0].toUpperCase() + spaced.substring(1);
  }

  // ── Actor ─────────────────────────────────────────────────────────────────

  static bool isSelf(AuditEntryDto e, String? selfUserId) =>
      selfUserId != null && e.userId == selfUserId;

  /// Display name of the actor (never a UUID).
  static String actorName(AuditEntryDto e, {String? selfUserId}) {
    if (isSelf(e, selfUserId)) return 'Vous';
    final name = e.actorName?.trim();
    if (name != null && name.isNotEmpty) return name;
    final label = _string(_decodeMap(e.metadata)['actorLabel']);
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
    var phrase = info.phrase;
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

  // ── Object ────────────────────────────────────────────────────────────────

  /// « Sur quoi »: label at action time, sale amount, else the type noun.
  static String objectLabel(AuditEntryDto e) {
    if (e.entityType == 'Sale') {
      final total = saleTotal(e);
      if (total != null) return 'Vente de ${formatXaf(total)}';
    }
    return _readableLabel(e) ??
        _typeNouns[e.entityType] ??
        humanize(e.entityType);
  }

  /// Sale total from `value_after.totalAmount` (also on legacy rows).
  static num? saleTotal(AuditEntryDto e) {
    final v = _decodeMap(e.valueAfter)['totalAmount'];
    return v is num ? v : null;
  }

  static String? _readableLabel(AuditEntryDto e) {
    final label = e.entityLabel?.trim();
    if (label != null && label.isNotEmpty) return label;
    // Legacy rows (before v1s-18-6): the name sits in value_after.
    final after = _decodeMap(e.valueAfter);
    for (final k in const ['productName', 'name', 'newName', 'storeName']) {
      final s = _string(after[k]);
      if (s != null) return s;
    }
    return null;
  }

  // ── Changes ───────────────────────────────────────────────────────────────

  /// Keys that are context or technical ids, never shown as changes.
  static const _hiddenChangeKeys = {
    'actorLabel',
    'storeId',
    'occurredAt',
    'saleId',
    'sessionId',
    'variantId',
    'productId',
    'parentId',
    'triggerProductId',
    'cancelledBy',
    'tenantId',
    'userId',
    'storeIds',
  };

  /// Context keys found in `value_after` of legacy rows.
  static const _contextKeys = {
    'justification',
    'notes',
    'note',
    'motif',
    'reason',
    'scope',
    'isAutomatic',
    'forcedProducts',
    'draftProductIds',
  };

  /// Field-by-field « Avant → Après » rows.
  static List<AuditChange> changes(AuditEntryDto e) {
    final beforeRaw = _decode(e.valueBefore);
    final afterRaw = _decode(e.valueAfter);
    // Whole snapshots that are lists (sale items).
    if (beforeRaw is List || afterRaw is List) {
      if (beforeRaw is Map || afterRaw is Map) {
        // Mixed shapes: fall through with the map side only.
      } else {
        return [
          AuditChange(
              fieldLabel('itemsSnapshot'),
              beforeRaw is List ? _items(beforeRaw.length) : '—',
              afterRaw is List ? _items(afterRaw.length) : '—'),
        ];
      }
    }
    final before =
        beforeRaw is Map ? beforeRaw.cast<String, dynamic>() : const {};
    final after = afterRaw is Map ? afterRaw.cast<String, dynamic>() : const {};
    final keys = <String>[...after.keys];
    for (final k in before.keys) {
      if (!keys.contains(k)) keys.add(k);
    }
    final rows = <AuditChange>[];
    for (final k in keys) {
      if (_hiddenChangeKeys.contains(k) || _contextKeys.contains(k)) continue;
      final b = before[k];
      final a = after[k];
      if (b == null && a == null) continue;
      rows.add(
          AuditChange(fieldLabel(k), formatValue(k, b), formatValue(k, a)));
    }
    return rows;
  }

  static const _fieldLabels = {
    'productName': 'Produit',
    'productSku': 'Référence',
    'name': 'Nom',
    'newName': 'Nouveau nom',
    'type': 'Type',
    'archived': 'Archivé',
    'active': 'Active',
    'deactivated': 'Désactivée',
    'status': 'Statut',
    'totalAmount': 'Montant total',
    'discountAmount': 'Remise',
    'appliedPrice': 'Prix appliqué',
    'cataloguePrice': 'Prix catalogue',
    'totalRevenue': 'Chiffre d\'affaires',
    'totalSales': 'Nombre de ventes',
    'price': 'Prix',
    'sellingPrice': 'Prix de vente',
    'purchasePrice': 'Prix d\'achat',
    'costPrice': 'Prix de revient',
    'unitPrice': 'Prix unitaire',
    'quantity': 'Quantité',
    'quantityBefore': 'Quantité avant',
    'quantityChange': 'Variation',
    'quantityAfter': 'Quantité après',
    'movementType': 'Type de mouvement',
    'currentQuantity': 'Quantité actuelle',
    'threshold': 'Seuil d\'alerte',
    'lowStockThreshold': 'Seuil d\'alerte',
    'requestedQuantity': 'Quantité demandée',
    'availableQuantity': 'Quantité disponible',
    'theoretical': 'Stock théorique',
    'physical': 'Stock compté',
    'ecart': 'Écart',
    'adjustmentCount': 'Ajustements',
    'role': 'Rôle',
    'fieldsChanged': 'Champs modifiés',
    'forcedReset': 'Changement de mot de passe imposé',
    'itemsSnapshot': 'Articles',
    'scheduledDeletionAt': 'Suppression prévue le',
    'cancelledAt': 'Annulée le',
    'storeName': 'Boutique',
    'sectorType': 'Secteur',
    'categoriesCreated': 'Catégories créées',
    'tenantCode': 'Code du compte',
    'description': 'Description',
    'barcode': 'Code-barres',
    'unit': 'Unité',
  };

  static String fieldLabel(String key) => _fieldLabels[key] ?? humanize(key);

  static const _amountKeys = {
    'totalAmount',
    'discountAmount',
    'appliedPrice',
    'cataloguePrice',
    'totalRevenue',
    'price',
    'sellingPrice',
    'purchasePrice',
    'costPrice',
    'unitPrice',
  };

  static const _quantityKeys = {
    'quantity',
    'quantityBefore',
    'quantityChange',
    'quantityAfter',
    'currentQuantity',
    'threshold',
    'lowStockThreshold',
    'requestedQuantity',
    'availableQuantity',
    'theoretical',
    'physical',
    'ecart',
  };

  static const _signedKeys = {'quantityChange', 'ecart'};

  static bool isAmountKey(String k) =>
      _amountKeys.contains(k) || k.endsWith('Price') || k.endsWith('Amount');

  /// Keys whose values are codes; only these are translated, so a product
  /// literally named « SALE » stays « SALE ».
  static const _codeKeys = {
    'status', 'type', 'movementType', 'role', 'scope', 'source',
    'previousRole', 'newRole', 'storeType',
  };

  static const _codes = {
    'DRAFT': 'Brouillon',
    'ACTIVE': 'Actif',
    'INACTIVE': 'Inactif',
    'PENDING_VALIDATION': 'En attente de validation',
    'COMPLETED': 'Terminée',
    'CANCELLED': 'Annulé(e)',
    'VALIDATED': 'Validé',
    'IN_PROGRESS': 'En cours',
    'SALE': 'Vente',
    'PURCHASE': 'Réception',
    'ADJUSTMENT': 'Ajustement',
    'TRANSFER_OUT': 'Transfert (sortie)',
    'TRANSFER_IN': 'Transfert (entrée)',
    'SALE_CANCELLED': 'Annulation de vente',
    'INVENTORY': 'Inventaire',
    'OWNER': 'Propriétaire',
    'EMPLOYEE': 'Employé',
    'MANAGER': 'Gérant',
    'STORE': 'Boutique',
    'WAREHOUSE': 'Entrepôt',
    'FULL': 'Complet',
    'CATEGORY': 'Par catégorie',
    'PARTIAL': 'Partiel',
  };

  /// Formats one value: XAF amounts, units, translated codes, dates, lists.
  static String formatValue(String key, Object? v) {
    if (v == null) return '—';
    if (v is bool) return v ? 'Oui' : 'Non';
    if (v is List) {
      if (v.every((x) => x is String) && key == 'fieldsChanged') {
        return v.map((x) => humanize(x as String)).join(', ');
      }
      return _items(v.length);
    }
    if (v is Map) return jsonEncode(v);
    if (v is num) {
      if (isAmountKey(key)) return formatXaf(v);
      if (_quantityKeys.contains(key)) {
        final sign = _signedKeys.contains(key) && v > 0 ? '+' : '';
        return '$sign${_number(v)} unité(s)';
      }
      return _number(v);
    }
    final s = v.toString();
    if (s.isEmpty) return '—';
    if (isAmountKey(key)) {
      final n = num.tryParse(s);
      if (n != null) return formatXaf(n);
    }
    if (_codeKeys.contains(key)) {
      final code = _codes[s];
      if (code != null) return code;
    }
    if (key.endsWith('At')) {
      final d = DateTime.tryParse(s);
      if (d != null) return formatDateTime(d);
    }
    return s;
  }

  static String _items(int n) => '$n article(s)';

  // ── Context ───────────────────────────────────────────────────────────────

  static const _contextLabels = {
    'justification': 'Justification',
    'notes': 'Motif',
    'note': 'Motif',
    'motif': 'Motif',
    'reason': 'Motif',
    'scope': 'Périmètre',
    'forcedProducts': 'Produits forcés',
    'draftProductIds': 'Produits en brouillon',
  };

  /// Keys of `metadata` never shown in « Contexte ».
  static const _hiddenContextKeys = {
    'actorLabel',
    'storeId',
    'occurredAt',
    'saleId',
    'sessionId',
    'variantId',
    'productId',
    'parentId',
    'triggerProductId',
    'cancelledBy',
  };

  /// « Contexte » rows from `metadata` (or, for legacy rows, the context keys
  /// still in `value_after`). Empty when there is nothing to show.
  static List<AuditContextItem> context(AuditEntryDto e) {
    final meta = _decodeMap(e.metadata);
    final Map<String, dynamic> source;
    if (meta.isNotEmpty) {
      source = meta;
    } else {
      final after = _decodeMap(e.valueAfter);
      source = {
        for (final k in _contextKeys)
          if (after.containsKey(k)) k: after[k],
      };
    }
    final items = <AuditContextItem>[];
    source.forEach((k, v) {
      if (_hiddenContextKeys.contains(k) || v == null) return;
      if (k == 'isAutomatic') {
        items.add(AuditContextItem('Déclenchement',
            v == true || v == 'true' ? 'Automatique' : 'Manuel'));
        return;
      }
      final value = v is List
          ? _items(v.length)
          : (v is String
              ? (_codeKeys.contains(k) ? (_codes[v] ?? v) : v)
              : formatValue(k, v));
      if (value.trim().isEmpty) return;
      items.add(AuditContextItem(_contextLabels[k] ?? humanize(k), value));
    });
    return items;
  }

  // ── Where / how ───────────────────────────────────────────────────────────

  static String sourceLabel(AuditEntryDto e) {
    switch (e.source) {
      case 'ONLINE':
        return 'En ligne';
      case 'SYNC':
        final device = deviceLabel(e.deviceId);
        return device == null
            ? 'Synchronisé depuis l\'appareil'
            : 'Synchronisé depuis l\'appareil $device';
      case 'SYSTEM':
        return 'Automatique (système)';
      case null:
        return 'Non renseigné';
      default:
        return humanize(e.source!);
    }
  }

  /// Short device id (last 8 characters) for display.
  static String? deviceLabel(String? deviceId) {
    final d = deviceId?.trim();
    if (d == null || d.isEmpty) return null;
    return d.length > 12 ? '…${d.substring(d.length - 8)}' : d;
  }

  // ── Formatting ────────────────────────────────────────────────────────────

  /// `15 000 XAF` (integer XAF, space grouping).
  static String formatXaf(num amount) => '${_number(amount.round())} XAF';

  static String _number(num v) {
    final isInt = v == v.roundToDouble();
    final negative = v < 0;
    final abs = v.abs();
    final intPart = abs.truncate().toString();
    final grouped = StringBuffer();
    for (var i = 0; i < intPart.length; i++) {
      if (i > 0 && (intPart.length - i) % 3 == 0) grouped.write(' ');
      grouped.write(intPart[i]);
    }
    var out = grouped.toString();
    if (!isInt) {
      final dec = abs.toString().split('.');
      if (dec.length == 2) out = '$out,${dec[1]}';
    }
    return negative ? '-$out' : out;
  }

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

  // ── JSON helpers ──────────────────────────────────────────────────────────

  static Object? _decode(String? raw) {
    if (raw == null || raw.trim().isEmpty) return null;
    try {
      return jsonDecode(raw);
    } catch (_) {
      return null;
    }
  }

  static Map<String, dynamic> _decodeMap(String? raw) {
    final v = _decode(raw);
    return v is Map ? v.cast<String, dynamic>() : const {};
  }

  static String? _string(Object? v) {
    if (v is! String) return null;
    final s = v.trim();
    return s.isEmpty ? null : s;
  }
}
