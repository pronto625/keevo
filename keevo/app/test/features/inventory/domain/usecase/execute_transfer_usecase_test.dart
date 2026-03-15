import 'package:flutter_test/flutter_test.dart';
import 'package:keevo/features/inventory/domain/model/stock_transfer_model.dart';
import 'package:keevo/features/inventory/domain/repository/stock_transfer_repository.dart';
import 'package:keevo/features/inventory/domain/usecase/execute_transfer_usecase.dart';

/// Minimal stub — avoids Mockito codegen dependency for a pure domain test.
class _FakeRepo implements StockTransferRepository {
  StockTransferModel? nextResult;
  Map<String, dynamic>? lastArgs;

  @override
  Future<StockTransferModel> executeTransfer({
    required String sourceStoreId,
    required String destinationStoreId,
    required String productId,
    String? variantId,
    required int quantity,
    String? notes,
  }) async {
    lastArgs = {
      'sourceStoreId': sourceStoreId,
      'destinationStoreId': destinationStoreId,
      'productId': productId,
      'variantId': variantId,
      'quantity': quantity,
      'notes': notes,
    };
    return nextResult!;
  }

  @override
  Future<List<StockTransferModel>> getHistory({
    String? sourceStoreId,
    String? destinationStoreId,
    String? productId,
    int page = 0,
    int pageSize = 20,
  }) async => [];

  @override
  Future<StockTransferModel> completeTransfer(String transferId) async =>
      throw UnimplementedError();
}

void main() {
  late _FakeRepo repo;
  late ExecuteTransferUseCase useCase;

  setUp(() {
    repo = _FakeRepo();
    useCase = ExecuteTransferUseCase(repo);
  });

  group('ExecuteTransferUseCase', () {
    test('throws ArgumentError when sourceStoreId == destinationStoreId', () {
      expect(
        () => useCase.execute(
          sourceStoreId: 'same',
          destinationStoreId: 'same',
          productId: 'prod-1',
          quantity: 5,
        ),
        throwsA(isA<ArgumentError>()),
      );
    });

    test('throws ArgumentError when quantity <= 0', () {
      expect(
        () => useCase.execute(
          sourceStoreId: 'src-1',
          destinationStoreId: 'dst-1',
          productId: 'prod-1',
          quantity: 0,
        ),
        throwsA(isA<ArgumentError>()),
      );
    });

    test('calls repository.executeTransfer with correct params when valid', () async {
      repo.nextResult = StockTransferModel(
        id: 'tf-1',
        sourceStoreId: 'src-1',
        destinationStoreId: 'dst-1',
        productId: 'prod-1',
        quantity: 5,
        actorId: 'actor-1',
        occurredAt: DateTime(2026, 3, 14),
        status: 'COMPLETED',
      );

      await useCase.execute(
        sourceStoreId: 'src-1',
        destinationStoreId: 'dst-1',
        productId: 'prod-1',
        quantity: 5,
        notes: 'test',
      );

      expect(repo.lastArgs?['sourceStoreId'], 'src-1');
      expect(repo.lastArgs?['destinationStoreId'], 'dst-1');
      expect(repo.lastArgs?['quantity'], 5);
      expect(repo.lastArgs?['notes'], 'test');
    });

    test('returns StockTransferModel from repository on success', () async {
      final expected = StockTransferModel(
        id: 'tf-99',
        sourceStoreId: 'src-1',
        destinationStoreId: 'dst-1',
        productId: 'prod-1',
        quantity: 3,
        actorId: 'actor-1',
        occurredAt: DateTime(2026, 3, 14),
        status: 'COMPLETED',
      );
      repo.nextResult = expected;

      final result = await useCase.execute(
        sourceStoreId: 'src-1',
        destinationStoreId: 'dst-1',
        productId: 'prod-1',
        quantity: 3,
      );

      expect(result.id, 'tf-99');
      expect(result.status, 'COMPLETED');
    });
  });
}
