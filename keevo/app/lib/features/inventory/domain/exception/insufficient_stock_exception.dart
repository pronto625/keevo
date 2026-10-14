/// Thrown when a transfer asks for more than the source store holds locally.
/// Carries a French [message] shown under the quantity field (it used to be a
/// `StateError` displayed as « Une erreur inattendue » — device test).
class InsufficientStockException implements Exception {
  final int available;
  final int requested;

  const InsufficientStockException({
    required this.available,
    required this.requested,
  });

  String get message => available <= 0
      ? 'Stock insuffisant : aucune unité disponible dans cette boutique'
      : 'Stock insuffisant : $available disponible${available > 1 ? 's' : ''}';

  @override
  String toString() => message;
}
