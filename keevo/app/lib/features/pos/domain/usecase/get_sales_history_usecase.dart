import '../model/sale_model.dart';
import '../model/sales_history_filter.dart';
import '../repository/sale_repository.dart';

/// GetSalesHistoryUseCase — Retrieves sales history with filters.
///
/// Story 4.4 — Clôture Journalière & Historique des Ventes.
class GetSalesHistoryUseCase {
  final SaleRepository _repository;

  GetSalesHistoryUseCase({required SaleRepository repository})
      : _repository = repository;

  /// Execute the sales history query.
  ///
  /// Returns sales matching the filter criteria, sorted by occurredAt DESC.
  /// For EMPLOYEE role, only returns own sales.
  /// For OWNER role, returns all store sales (can optionally filter by employee).
  Future<List<Sale>> execute(SalesHistoryFilter filter) async {
    return _repository.getSalesHistory(filter);
  }
}
