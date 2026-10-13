/// On-device export schema (v1s-18-5) — the app copy of the server export
/// column contract (`keevo/contracts/export-datasets.json`, pinned server
/// side by `ExportContractFileTest`, app side by
/// `test/features/export/domain/local_export_contract_test.dart`).
///
/// The local CSV must be indistinguishable in shape from the server CSV:
/// same datasets, column keys, French labels, types and code → label maps.
/// When the contract file changes, update this catalogue until the app test
/// passes again.
///
/// v1s-16-20 — no technical data in a file: no id column, unknown codes
/// humanized, file / zip entry names from the French label ([exportSlug]).
library;

import '../../../audit/domain/audit_format.dart';
import '../../../audit/domain/audit_labels.dart';

/// Cell type of an export column (server `ExportColumnType`).
enum LocalColumnType {
  text('TEXT'),
  integer('INTEGER'),
  amountXaf('AMOUNT_XAF'),
  decimal('DECIMAL'),
  boolean('BOOLEAN'),
  date('DATE'),
  datetime('DATETIME');

  /// Name in the contract file.
  final String contractName;

  const LocalColumnType(this.contractName);
}

/// One column: stable camelCase key, French header label, cell type.
class LocalExportColumn {
  final String key;
  final String label;
  final LocalColumnType type;

  const LocalExportColumn(this.key, this.label, this.type);
}

/// One exportable dataset and the filters it applies.
class LocalExportDataset {
  final String key;
  final String label;
  final bool storeFilter;
  final bool periodFilter;
  final List<String> criteria;
  final List<LocalExportColumn> columns;

  const LocalExportDataset({
    required this.key,
    required this.label,
    required this.storeFilter,
    required this.periodFilter,
    this.criteria = const [],
    required this.columns,
  });
}

/// Dataset of [key] in [localExportCatalog], or `null`.
LocalExportDataset? localExportDataset(String key) {
  for (final d in localExportCatalog) {
    if (d.key == key) return d;
  }
  return null;
}

/// Owner fallback name of an actor without employee row (server
/// `ExportValues.OWNER_LABEL`).
const exportOwnerLabel = 'Propriétaire';

/// Stock states of `stock_levels.stockStatus` (server
/// `StockLevelsExportDatasetProvider`).
const exportStockOutOfStock = 'Rupture';
const exportStockLow = 'Stock bas';
const exportStockOk = 'OK';

/// French label of [code] in [labels]: `null` stays `null`, an unknown code
/// is humanized (`KIOSK` → « Kiosk »), never written raw (server
/// `ExportValues.frLabel`).
String? exportLabel(Map<String, String>? labels, String? code) {
  if (code == null) return null;
  return labels?[code] ?? AuditFormat.humanize(code);
}

/// Readable reference of a sale (server `ExportValues.saleReference`):
/// « Vente du 12/10/2026 à 14:32:05 — Boutique Centre » in WAT, without the
/// store part when unknown. Shared by `sales` and `sale_items`.
String? exportSaleReference(DateTime? occurredAt, String? store) {
  if (occurredAt == null) return null;
  final w = AuditFormat.toWat(occurredAt);
  String two(int v) => v.toString().padLeft(2, '0');
  final ref = 'Vente du ${two(w.day)}/${two(w.month)}/${w.year} à '
      '${two(w.hour)}:${two(w.minute)}:${two(w.second)}';
  final s = store?.trim();
  return s == null || s.isEmpty ? ref : '$ref — $s';
}

const _accents = {
  'à': 'a', 'á': 'a', 'â': 'a', 'ä': 'a', 'ã': 'a', 'å': 'a', //
  'ç': 'c',
  'è': 'e', 'é': 'e', 'ê': 'e', 'ë': 'e',
  'ì': 'i', 'í': 'i', 'î': 'i', 'ï': 'i',
  'ñ': 'n',
  'ò': 'o', 'ó': 'o', 'ô': 'o', 'ö': 'o', 'õ': 'o',
  'ù': 'u', 'ú': 'u', 'û': 'u', 'ü': 'u',
  'ý': 'y', 'ÿ': 'y',
  'œ': 'oe', 'æ': 'ae',
};

/// ASCII file-name part of a French label (server `ExportValues.slug`):
/// « Journal d'audit » → `journal-d-audit`; `null` when nothing remains.
String? exportSlug(String? label) {
  if (label == null) return null;
  final ascii = label
      .toLowerCase()
      .split('')
      .map((c) => _accents[c] ?? c)
      .join();
  final s = ascii
      .replaceAll(RegExp(r'[^a-z0-9]+'), '-')
      .replaceAll(RegExp(r'^-+|-+$'), '');
  return s.isEmpty ? null : s;
}

/// File / zip entry name part of dataset [key]: the slug of its French label.
String exportDatasetSlug(String key) =>
    exportSlug(localExportDataset(key)?.label) ?? 'export';

/// Datasets in the server catalogue order.
const localExportCatalog = <LocalExportDataset>[
  LocalExportDataset(
    key: 'products',
    label: 'Produits',
    storeFilter: true,
    periodFilter: false,
    columns: [
      LocalExportColumn('sku', 'Référence', LocalColumnType.text),
      LocalExportColumn('name', 'Nom', LocalColumnType.text),
      LocalExportColumn('category', 'Catégorie', LocalColumnType.text),
      LocalExportColumn('status', 'Statut', LocalColumnType.text),
      LocalExportColumn(
          'salePrice', 'Prix de vente', LocalColumnType.amountXaf),
      LocalExportColumn('buyPrice', 'Prix d\'achat', LocalColumnType.amountXaf),
      LocalExportColumn(
          'transportCost', 'Frais de transport', LocalColumnType.amountXaf),
      LocalExportColumn(
          'minimumThreshold', 'Seuil minimum', LocalColumnType.integer),
      LocalExportColumn('stockQuantity', 'Stock', LocalColumnType.integer),
      LocalExportColumn('suppliers', 'Fournisseurs', LocalColumnType.text),
      LocalExportColumn('archived', 'Archivé', LocalColumnType.boolean),
      LocalExportColumn('createdAt', 'Créé le', LocalColumnType.datetime),
      LocalExportColumn('updatedAt', 'Modifié le', LocalColumnType.datetime),
    ],
  ),
  LocalExportDataset(
    key: 'categories',
    label: 'Catégories',
    storeFilter: false,
    periodFilter: false,
    columns: [
      LocalExportColumn('name', 'Nom', LocalColumnType.text),
      LocalExportColumn('parent', 'Catégorie parente', LocalColumnType.text),
      LocalExportColumn('active', 'Active', LocalColumnType.boolean),
      LocalExportColumn('custom', 'Personnalisée', LocalColumnType.boolean),
      LocalExportColumn('createdAt', 'Créée le', LocalColumnType.datetime),
    ],
  ),
  LocalExportDataset(
    key: 'stock_levels',
    label: 'Stocks',
    storeFilter: true,
    periodFilter: false,
    columns: [
      LocalExportColumn('store', 'Boutique', LocalColumnType.text),
      LocalExportColumn('sku', 'Référence', LocalColumnType.text),
      LocalExportColumn('product', 'Produit', LocalColumnType.text),
      LocalExportColumn('category', 'Catégorie', LocalColumnType.text),
      LocalExportColumn('quantity', 'Quantité', LocalColumnType.integer),
      LocalExportColumn(
          'minimumThreshold', 'Seuil minimum', LocalColumnType.integer),
      LocalExportColumn('stockStatus', 'État', LocalColumnType.text),
      LocalExportColumn('buyPrice', 'Prix d\'achat', LocalColumnType.amountXaf),
      LocalExportColumn(
          'stockValue', 'Valeur du stock', LocalColumnType.amountXaf),
      LocalExportColumn(
          'productArchived', 'Produit archivé', LocalColumnType.boolean),
      LocalExportColumn('updatedAt', 'Mis à jour le', LocalColumnType.datetime),
    ],
  ),
  LocalExportDataset(
    key: 'stock_movements',
    label: 'Mouvements de stock',
    storeFilter: true,
    periodFilter: true,
    columns: [
      LocalExportColumn('occurredAt', 'Date', LocalColumnType.datetime),
      LocalExportColumn('store', 'Boutique', LocalColumnType.text),
      LocalExportColumn('sku', 'Référence', LocalColumnType.text),
      LocalExportColumn('product', 'Produit', LocalColumnType.text),
      LocalExportColumn('type', 'Type', LocalColumnType.text),
      LocalExportColumn(
          'quantityBefore', 'Quantité avant', LocalColumnType.integer),
      LocalExportColumn('quantityChange', 'Variation', LocalColumnType.integer),
      LocalExportColumn(
          'quantityAfter', 'Quantité après', LocalColumnType.integer),
      LocalExportColumn('actor', 'Auteur', LocalColumnType.text),
      LocalExportColumn('notes', 'Notes', LocalColumnType.text),
    ],
  ),
  LocalExportDataset(
    key: 'stock_transfers',
    label: 'Transferts',
    storeFilter: true,
    periodFilter: true,
    columns: [
      LocalExportColumn('occurredAt', 'Date', LocalColumnType.datetime),
      LocalExportColumn(
          'sourceStore', 'Boutique d\'origine', LocalColumnType.text),
      LocalExportColumn(
          'destinationStore', 'Boutique de destination', LocalColumnType.text),
      LocalExportColumn('sku', 'Référence', LocalColumnType.text),
      LocalExportColumn('product', 'Produit', LocalColumnType.text),
      LocalExportColumn('quantity', 'Quantité', LocalColumnType.integer),
      LocalExportColumn('status', 'Statut', LocalColumnType.text),
      LocalExportColumn('actor', 'Auteur', LocalColumnType.text),
      LocalExportColumn('notes', 'Notes', LocalColumnType.text),
    ],
  ),
  LocalExportDataset(
    key: 'sales',
    label: 'Ventes',
    storeFilter: true,
    periodFilter: true,
    columns: [
      LocalExportColumn(
          'reference', 'Référence de la vente', LocalColumnType.text),
      LocalExportColumn('occurredAt', 'Date', LocalColumnType.datetime),
      LocalExportColumn('store', 'Boutique', LocalColumnType.text),
      LocalExportColumn('employee', 'Vendeur', LocalColumnType.text),
      LocalExportColumn('client', 'Client', LocalColumnType.text),
      LocalExportColumn('status', 'Statut', LocalColumnType.text),
      LocalExportColumn(
          'paymentMode', 'Mode de paiement', LocalColumnType.text),
      LocalExportColumn('itemCount', 'Articles', LocalColumnType.integer),
      LocalExportColumn('discount', 'Remise', LocalColumnType.amountXaf),
      LocalExportColumn('total', 'Montant total', LocalColumnType.amountXaf),
    ],
  ),
  LocalExportDataset(
    key: 'sale_items',
    label: 'Lignes de vente',
    storeFilter: true,
    periodFilter: true,
    columns: [
      LocalExportColumn('sale', 'Vente', LocalColumnType.text),
      LocalExportColumn('occurredAt', 'Date', LocalColumnType.datetime),
      LocalExportColumn('store', 'Boutique', LocalColumnType.text),
      LocalExportColumn('employee', 'Vendeur', LocalColumnType.text),
      LocalExportColumn(
          'saleStatus', 'Statut de la vente', LocalColumnType.text),
      LocalExportColumn('sku', 'Référence', LocalColumnType.text),
      LocalExportColumn('product', 'Produit', LocalColumnType.text),
      LocalExportColumn('quantity', 'Quantité', LocalColumnType.integer),
      LocalExportColumn(
          'cataloguePrice', 'Prix catalogue', LocalColumnType.amountXaf),
      LocalExportColumn(
          'unitPrice', 'Prix appliqué', LocalColumnType.amountXaf),
      LocalExportColumn('subtotal', 'Sous-total', LocalColumnType.amountXaf),
    ],
  ),
  LocalExportDataset(
    key: 'day_closures',
    label: 'Clôtures',
    storeFilter: true,
    periodFilter: true,
    columns: [
      LocalExportColumn('closureDate', 'Jour', LocalColumnType.date),
      LocalExportColumn('closedAt', 'Clôturée le', LocalColumnType.datetime),
      LocalExportColumn('store', 'Boutique', LocalColumnType.text),
      LocalExportColumn('automatic', 'Automatique', LocalColumnType.boolean),
      LocalExportColumn('actor', 'Clôturée par', LocalColumnType.text),
      LocalExportColumn(
          'salesCount', 'Nombre de ventes', LocalColumnType.integer),
      LocalExportColumn(
          'revenue', 'Chiffre d\'affaires', LocalColumnType.amountXaf),
      LocalExportColumn('cashAmount', 'Espèces', LocalColumnType.amountXaf),
      LocalExportColumn(
          'momoAmount', 'Mobile Money', LocalColumnType.amountXaf),
      LocalExportColumn(
          'pendingSalesCount', 'Ventes en attente', LocalColumnType.integer),
      LocalExportColumn(
          'pendingSalesTotal', 'Montant en attente', LocalColumnType.amountXaf),
      LocalExportColumn('topProduct', 'Meilleur produit', LocalColumnType.text),
      LocalExportColumn('topProductQuantity', 'Quantité meilleur produit',
          LocalColumnType.integer),
    ],
  ),
  LocalExportDataset(
    key: 'inventory_sessions',
    label: 'Inventaires',
    storeFilter: true,
    periodFilter: true,
    columns: [
      LocalExportColumn('startedAt', 'Commencé le', LocalColumnType.datetime),
      LocalExportColumn('store', 'Boutique', LocalColumnType.text),
      LocalExportColumn('scope', 'Périmètre', LocalColumnType.text),
      LocalExportColumn('status', 'Statut', LocalColumnType.text),
      LocalExportColumn('startedBy', 'Commencé par', LocalColumnType.text),
      LocalExportColumn('completedAt', 'Validé le', LocalColumnType.datetime),
      LocalExportColumn('cancelledAt', 'Annulé le', LocalColumnType.datetime),
      LocalExportColumn(
          'countedProducts', 'Produits comptés', LocalColumnType.integer),
      LocalExportColumn(
          'gapProducts', 'Produits en écart', LocalColumnType.integer),
      LocalExportColumn('totalGap', 'Écart total', LocalColumnType.integer),
    ],
  ),
  LocalExportDataset(
    key: 'inventory_counts',
    label: 'Comptages d\'inventaire',
    storeFilter: true,
    periodFilter: true,
    columns: [
      LocalExportColumn('sessionStartedAt', 'Date de l\'inventaire',
          LocalColumnType.datetime),
      LocalExportColumn('store', 'Boutique', LocalColumnType.text),
      LocalExportColumn(
          'sessionStatus', 'Statut de l\'inventaire', LocalColumnType.text),
      LocalExportColumn('product', 'Produit', LocalColumnType.text),
      LocalExportColumn('variant', 'Variante', LocalColumnType.text),
      LocalExportColumn(
          'theoretical', 'Stock théorique', LocalColumnType.integer),
      LocalExportColumn('physical', 'Stock compté', LocalColumnType.integer),
      LocalExportColumn('gap', 'Écart', LocalColumnType.integer),
      LocalExportColumn('countedAt', 'Compté le', LocalColumnType.datetime),
      LocalExportColumn('countedBy', 'Compté par', LocalColumnType.text),
    ],
  ),
  LocalExportDataset(
    key: 'clients',
    label: 'Clients',
    storeFilter: false,
    periodFilter: false,
    columns: [
      LocalExportColumn('name', 'Nom', LocalColumnType.text),
      LocalExportColumn('phone', 'Téléphone', LocalColumnType.text),
      LocalExportColumn('email', 'E-mail', LocalColumnType.text),
      LocalExportColumn('notes', 'Notes', LocalColumnType.text),
      LocalExportColumn(
          'salesCount', 'Nombre d\'achats', LocalColumnType.integer),
      LocalExportColumn(
          'totalSpent', 'Total des achats', LocalColumnType.amountXaf),
      LocalExportColumn(
          'lastPurchaseAt', 'Dernier achat', LocalColumnType.datetime),
      LocalExportColumn('archived', 'Archivé', LocalColumnType.boolean),
      LocalExportColumn('createdAt', 'Créé le', LocalColumnType.datetime),
    ],
  ),
  LocalExportDataset(
    key: 'suppliers',
    label: 'Fournisseurs',
    storeFilter: false,
    periodFilter: false,
    columns: [
      LocalExportColumn('name', 'Nom', LocalColumnType.text),
      LocalExportColumn('phone', 'Téléphone', LocalColumnType.text),
      LocalExportColumn('email', 'E-mail', LocalColumnType.text),
      LocalExportColumn(
          'productCount', 'Produits fournis', LocalColumnType.integer),
      LocalExportColumn('archived', 'Archivé', LocalColumnType.boolean),
      LocalExportColumn('createdAt', 'Créé le', LocalColumnType.datetime),
    ],
  ),
  LocalExportDataset(
    key: 'employees',
    label: 'Équipe',
    storeFilter: true,
    periodFilter: false,
    columns: [
      LocalExportColumn('firstName', 'Prénom', LocalColumnType.text),
      LocalExportColumn('lastName', 'Nom', LocalColumnType.text),
      LocalExportColumn('phone', 'Téléphone', LocalColumnType.text),
      LocalExportColumn('store', 'Boutique', LocalColumnType.text),
      LocalExportColumn('status', 'Statut', LocalColumnType.text),
      LocalExportColumn('createdAt', 'Ajouté le', LocalColumnType.datetime),
    ],
  ),
  LocalExportDataset(
    key: 'stores',
    label: 'Boutiques',
    storeFilter: true,
    periodFilter: false,
    columns: [
      LocalExportColumn('name', 'Nom', LocalColumnType.text),
      LocalExportColumn('type', 'Type', LocalColumnType.text),
      LocalExportColumn('address', 'Adresse', LocalColumnType.text),
      LocalExportColumn('phone', 'Téléphone', LocalColumnType.text),
      LocalExportColumn('active', 'Active', LocalColumnType.boolean),
      LocalExportColumn('createdAt', 'Créée le', LocalColumnType.datetime),
    ],
  ),
  LocalExportDataset(
    key: 'reports',
    label: 'Rapports',
    storeFilter: true,
    periodFilter: true,
    columns: [
      LocalExportColumn('reportDate', 'Date du rapport', LocalColumnType.date),
      LocalExportColumn('type', 'Type', LocalColumnType.text),
      LocalExportColumn('store', 'Boutique', LocalColumnType.text),
      LocalExportColumn(
          'revenue', 'Chiffre d\'affaires', LocalColumnType.amountXaf),
      LocalExportColumn(
          'salesCount', 'Nombre de ventes', LocalColumnType.integer),
      LocalExportColumn('automatic', 'Automatique', LocalColumnType.boolean),
      LocalExportColumn('actor', 'Généré par', LocalColumnType.text),
      LocalExportColumn('deliveryStatus', 'Envoi', LocalColumnType.text),
      LocalExportColumn('createdAt', 'Généré le', LocalColumnType.datetime),
      LocalExportColumn('content', 'Contenu', LocalColumnType.text),
    ],
  ),
  LocalExportDataset(
    key: 'audit_log',
    label: 'Journal d\'audit',
    storeFilter: true,
    periodFilter: true,
    criteria: ['action', 'entityId', 'entityType', 'q', 'userId'],
    columns: [
      LocalExportColumn('occurredAt', 'Date', LocalColumnType.datetime),
      LocalExportColumn('actor', 'Auteur', LocalColumnType.text),
      LocalExportColumn('actorRole', 'Rôle', LocalColumnType.text),
      LocalExportColumn('action', 'Action', LocalColumnType.text),
      LocalExportColumn('entityType', 'Type d\'objet', LocalColumnType.text),
      LocalExportColumn('entityLabel', 'Objet', LocalColumnType.text),
      LocalExportColumn('store', 'Boutique', LocalColumnType.text),
      LocalExportColumn('source', 'Source', LocalColumnType.text),
      LocalExportColumn('changes', 'Changements', LocalColumnType.text),
      LocalExportColumn('details', 'Détails', LocalColumnType.text),
    ],
  ),
];

/// Code → French label maps, keyed `<dataset>.<columnKey>` (server provider
/// maps, see the contract file).
const exportLabels = <String, Map<String, String>>{
  'products.status': {
    'ACTIVE': 'Actif',
    'DRAFT': 'Brouillon',
  },
  'stores.type': {
    'STORE': 'Boutique',
    'WAREHOUSE': 'Entrepôt',
  },
  'stock_movements.type': {
    'ADJUSTMENT': 'Ajustement',
    'SALE': 'Vente',
    'SALE_CANCELLED': 'Annulation de vente',
    'STOCK_ENTRY': 'Entrée de stock',
    'TRANSFER_IN': 'Transfert entrant',
    'TRANSFER_OUT': 'Transfert sortant',
  },
  'stock_transfers.status': {
    'COMPLETED': 'Terminé',
    'CONFLICT': 'En conflit',
    'IN_TRANSIT': 'En transit',
    'PENDING_SYNC': 'En attente de synchronisation',
  },
  'sales.status': {
    'CANCELLED': 'Annulée',
    'COMPLETED': 'Validée',
    'PENDING_VALIDATION': 'En attente de validation',
  },
  'sales.paymentMode': {
    'CASH': 'Espèces',
    'MOBILE_MONEY': 'Mobile Money',
  },
  'sale_items.saleStatus': {
    'CANCELLED': 'Annulée',
    'COMPLETED': 'Validée',
    'PENDING_VALIDATION': 'En attente de validation',
  },
  'inventory_sessions.scope': {
    'FULL': 'Complet',
    'PARTIAL': 'Partiel',
  },
  'inventory_sessions.status': {
    'CANCELLED': 'Annulé',
    'IN_PROGRESS': 'En cours',
    'VALIDATED': 'Validé',
  },
  'inventory_counts.sessionStatus': {
    'CANCELLED': 'Annulé',
    'IN_PROGRESS': 'En cours',
    'VALIDATED': 'Validé',
  },
  'employees.status': {
    'ACTIVE': 'Actif',
    'INACTIVE': 'Inactif',
  },
  'reports.type': {
    'DAILY': 'Fin de journée',
    'DAILY_COMBINED': 'Fin de journée (toutes boutiques)',
    'WEEKLY': 'Hebdomadaire',
  },
  'reports.deliveryStatus': {
    'FAILED': 'Échec de l\'envoi',
    'IN_APP_ONLY': 'Dans l\'application uniquement',
    'PENDING': 'En attente',
    'SENT': 'Envoyé',
  },
  'audit_log.actorRole': {
    'EMPLOYEE': 'Employé',
    'OWNER': 'Propriétaire',
  },
  'audit_log.source': {
    'ONLINE': 'En ligne',
    'SYNC': 'Synchronisé',
    'SYSTEM': 'Système',
  },
  'audit_log.action': auditActionLabels,
  'audit_log.entityType': auditEntityTypeLabels,
};
