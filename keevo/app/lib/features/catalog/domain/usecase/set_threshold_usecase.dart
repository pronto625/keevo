import '../repository/stock_repository.dart';

/// SetThresholdUseCase — updates the minimum stock alert threshold.
///
/// Story 2.3.
class SetThresholdUseCase {
  final StockRepository _repository;

  const SetThresholdUseCase(this._repository);

  /// [minimumThreshold] must be >= 0. Pass 0 to disable alerting.
  Future<void> execute({
    required String productId,
    required int minimumThreshold,
  }) {
    if (minimumThreshold < 0) {
      throw ArgumentError('minimumThreshold cannot be negative');
    }
    return _repository.setThreshold(
      productId: productId,
      minimumThreshold: minimumThreshold,
    );
  }
}
