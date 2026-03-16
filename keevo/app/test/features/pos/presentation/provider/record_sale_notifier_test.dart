import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:keevo/features/pos/data/datasource/local_sale_datasource.dart';
import 'package:keevo/features/pos/domain/model/cart_item.dart';
import 'package:keevo/features/pos/domain/model/payment_mode_enum.dart';
import 'package:keevo/features/pos/domain/model/sale_model.dart';
import 'package:keevo/features/pos/domain/repository/sale_repository.dart';
import 'package:keevo/features/pos/domain/usecase/record_sale_usecase.dart';
import 'package:keevo/features/pos/presentation/provider/cart_provider.dart';
import 'package:keevo/features/pos/presentation/provider/pos_providers.dart';
import 'package:keevo/features/pos/presentation/provider/record_sale_notifier.dart';
import 'package:mocktail/mocktail.dart';

class MockSaleRepository extends Mock implements SaleRepository {}

class MockLocalSaleDataSource extends Mock implements LocalSaleDataSource {}

class FakeSale extends Fake implements Sale {}

CartItem _item({String id = 'p1', int price = 500, int qty = 1}) => CartItem(
      id: id,
      productId: id,
      productName: 'Savon',
      unitPrice: price,
      appliedUnitPrice: price,
      quantity: qty,
    );

void main() {
  late MockSaleRepository mockRepo;
  late MockLocalSaleDataSource mockLocalDs;

  setUpAll(() {
    registerFallbackValue(FakeSale());
  });

  setUp(() {
    mockRepo = MockSaleRepository();
    mockLocalDs = MockLocalSaleDataSource();
  });

  ProviderContainer createContainer({List<CartItem> cart = const []}) {
    final container = ProviderContainer(
      overrides: [
        saleRepositoryProvider.overrideWithValue(mockRepo),
        localSaleDataSourceProvider.overrideWithValue(mockLocalDs),
        recordSaleUseCaseProvider
            .overrideWithValue(RecordSaleUseCase(mockRepo)),
      ],
    );

    // Pre-populate cart
    final notifier = container.read(cartProvider.notifier);
    for (final item in cart) {
      notifier.addItem(item);
    }

    return container;
  }

  group('RecordSaleNotifier', () {
    test('submit success clears cart', () async {
      when(() => mockLocalDs.getAvailableStock(any(), any()))
          .thenAnswer((_) async => 10);
      when(() => mockRepo.recordSale(any())).thenAnswer((_) async {});

      final container = createContainer(cart: [_item()]);
      final notifier = container.read(recordSaleNotifierProvider.notifier);

      await notifier.submit(
        cart: [_item()],
        mode: PaymentModeEnum.cash,
        storeId: 'store-1',
        employeeId: 'emp-1',
      );

      final state = container.read(recordSaleNotifierProvider);
      expect(state, isA<RecordSaleSuccess>());
      expect(container.read(cartProvider), isEmpty);
    });

    test('submit insufficient stock returns error', () async {
      when(() => mockLocalDs.getAvailableStock('p1', 'store-1'))
          .thenAnswer((_) async => 0);

      final container = createContainer(cart: [_item()]);
      final notifier = container.read(recordSaleNotifierProvider.notifier);

      await notifier.submit(
        cart: [_item()],
        mode: PaymentModeEnum.cash,
        storeId: 'store-1',
        employeeId: 'emp-1',
      );

      final state = container.read(recordSaleNotifierProvider);
      expect(state, isA<RecordSaleError>());
      expect((state as RecordSaleError).message, 'INSUFFICIENT_STOCK');
      verifyNever(() => mockRepo.recordSale(any()));
    });

    test('submit offline still records locally via use case', () async {
      when(() => mockLocalDs.getAvailableStock(any(), any()))
          .thenAnswer((_) async => 10);
      // The repository recordSale will be called — it handles
      // offline internally (catches DioException in background push).
      when(() => mockRepo.recordSale(any())).thenAnswer((_) async {});

      final container = createContainer(cart: [_item()]);
      final notifier = container.read(recordSaleNotifierProvider.notifier);

      await notifier.submit(
        cart: [_item()],
        mode: PaymentModeEnum.mobileMoney,
        storeId: 'store-1',
        employeeId: 'emp-1',
        mobileRef: 'ref-123',
      );

      final state = container.read(recordSaleNotifierProvider);
      expect(state, isA<RecordSaleSuccess>());
      verify(() => mockRepo.recordSale(any())).called(1);
    });
  });
}
