import '../../utils/server_date.dart';

/// SyncConflict — Domain model for a sync conflict event from the backend.
///
/// Immutable value object parsed from GET /api/v1/sync/conflicts response
/// and from push response conflictData.
class SyncConflict {
  final String id;
  final String operationId;
  final String operationType;
  final String? entityId;
  final String? entityType;
  final String conflictType;
  final String strategy;
  final Map<String, dynamic>? conflictData;
  final DateTime resolvedAt;

  SyncConflict({
    required this.id,
    required this.operationId,
    required this.operationType,
    this.entityId,
    this.entityType,
    required this.conflictType,
    required this.strategy,
    this.conflictData,
    required this.resolvedAt,
  });

  factory SyncConflict.fromJson(Map<String, dynamic> json) {
    return SyncConflict(
      id: json['id'] as String,
      operationId: json['operationId'] as String,
      operationType: json['operationType'] as String,
      entityId: json['entityId'] as String?,
      entityType: json['entityType'] as String?,
      conflictType: json['conflictType'] as String,
      strategy: json['strategy'] as String,
      conflictData: json['conflictData'] as Map<String, dynamic>?,
      resolvedAt: parseServerDate(json['resolvedAt'] as String),
    );
  }

  /// Human-readable title for display in the conflict log.
  String get displayTitle {
    switch (conflictType) {
      case 'STOCK_NEGATIVE':
        final productName = conflictData?['productName'] ?? 'Produit';
        final resultingStock = conflictData?['resultingStock'] ?? '?';
        return 'Stock négatif — $productName: $resultingStock unités';
      case 'LAST_WRITE_WINS':
        final et = entityType != null ? entityLabel(entityType!) : 'Élément';
        return 'Modification écrasée — $et: dernière version conservée';
      default:
        return 'Conflit résolu — ${typeLabel(conflictType)}';
    }
  }

  // ── v1s-16-20: readable labels, never a raw code, id or JSON ──────────────

  static const _types = {
    'STOCK_NEGATIVE': 'Stock négatif',
    'LAST_WRITE_WINS': 'Modification écrasée',
  };

  static const _strategies = {
    'LAST_WRITE_WINS': 'Dernière modification conservée',
    'DELTA_SUM': 'Quantités additionnées',
  };

  static const _entities = {
    'PRODUCT': 'Produit',
    'Product': 'Produit',
    'CLIENT': 'Client',
    'Client': 'Client',
    'SUPPLIER': 'Fournisseur',
    'Supplier': 'Fournisseur',
    'EMPLOYEE': 'Employé',
    'Employee': 'Employé',
    'STORE': 'Boutique',
    'Store': 'Boutique',
    'CATEGORY': 'Catégorie',
    'Category': 'Catégorie',
  };

  static const _dataLabels = {
    'productName': 'Produit',
    'resultingStock': 'Stock obtenu',
    'previousStock': 'Stock précédent',
    'deltaApplied': 'Quantité appliquée',
    'entityType': 'Élément',
    'overwrittenTimestamp': 'Version remplacée du',
    'winnerTimestamp': 'Version conservée du',
  };

  static final _uuid = RegExp(
      r'[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}');

  /// `FOO_BAR` → « Foo bar ».
  static String humanize(String code) {
    final s = code
        .replaceAllMapped(
            RegExp(r'([a-z0-9])([A-Z])'), (m) => '${m[1]} ${m[2]}')
        .replaceAll('_', ' ')
        .trim()
        .toLowerCase();
    return s.isEmpty ? code : s[0].toUpperCase() + s.substring(1);
  }

  static String typeLabel(String type) => _types[type] ?? humanize(type);

  static String strategyLabel(String strategy) =>
      _strategies[strategy] ?? humanize(strategy);

  /// French noun of an entity type; an unknown / unmapped one (`UNKNOWN`,
  /// a raw operation code) reads « Élément », never English.
  static String entityLabel(String type) =>
      _entities[type] ?? _entities[type.toUpperCase()] ?? 'Élément';

  /// Readable (label, value) rows of [conflictData]: ids, UUID values,
  /// nested objects and the type marker are left out.
  List<(String, String)> get detailRows {
    final data = conflictData;
    if (data == null) return const [];
    final rows = <(String, String)>[];
    data.forEach((k, v) {
      if (v == null || v is Map || v is List) return;
      if (k == 'conflictType' || k == 'id' || k.endsWith('Id')) return;
      final raw = v.toString();
      if (raw.isEmpty || _uuid.hasMatch(raw)) return;
      final String value;
      if (k == 'entityType') {
        value = entityLabel(raw);
      } else if (k.endsWith('Timestamp') || k.endsWith('At')) {
        final d = DateTime.tryParse(raw);
        value = d == null ? raw : formatDate(d);
      } else if (RegExp(r'^[A-Z0-9]+(_[A-Z0-9]+)+$').hasMatch(raw)) {
        value = humanize(raw);
      } else {
        value = raw;
      }
      rows.add((_dataLabels[k] ?? humanize(k), value));
    });
    return rows;
  }

  /// `dd/MM/yyyy HH:mm` in local time.
  static String formatDate(DateTime d) {
    final l = d.toLocal();
    String two(int v) => v.toString().padLeft(2, '0');
    return '${two(l.day)}/${two(l.month)}/${l.year} ${two(l.hour)}:${two(l.minute)}';
  }
}
