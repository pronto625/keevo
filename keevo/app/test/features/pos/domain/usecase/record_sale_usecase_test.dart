import 'package:flutter_test/flutter_test.dart';
import 'package:keevo/features/pos/domain/model/cart_item.dart';
import 'package:keevo/features/pos/domain/model/payment_mode_enum.dart';
import 'package:keevo/features/pos/domain/model/sale_model.dart';
import 'package:keevo/features/pos/domain/repository/sale_repository.dart';
import 'package:keevo/features/pos/domain/usecase/record_sale_usecase.dart';
import 'package:mocktail/mocktail.dart';

class MockSaleRepository extends Mock implements SaleRepository {}

class FakeSale extends Fake implements Sale {}

void main() {
  setUpAll(() => registerFallbackValue(FakeSale()));

  // Backstop behind the cart clamp: a negative total is rejected by the
  // backend on every sync, whatever path the discount came from.
  test('a discount above the subtotal is clamped: total 0, never negative',
      () async {
    final repo = MockSaleRepository();
    when(() => repo.recordSale(any())).thenAnswer((_) async {});

    final sale = await RecordSaleUseCase(repo).execute(
      cart: [
        CartItem(
          id: 'p1',
          productId: 'p1',
          productName: 'Savon',
          unitPrice: 500,
          appliedUnitPrice: 500,
          quantity: 2,
        ),
      ],
      mode: PaymentModeEnum.cash,
      storeId: 'store-1',
      employeeId: 'emp-1',
      discountAmount: 1500,
    );

    expect(sale.discountAmount, 1000);
    expect(sale.totalAmount, 0);
    final recorded = verify(() => repo.recordSale(captureAny())).captured.single
        as Sale;
    expect(recorded.totalAmount, 0);
  });
}
