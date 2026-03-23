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
      resolvedAt: DateTime.parse(json['resolvedAt'] as String),
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
        final et = entityType ?? 'Entité';
        return 'Modification écrasée — $et: dernière version conservée';
      default:
        return 'Conflit résolu — $conflictType';
    }
  }
}
