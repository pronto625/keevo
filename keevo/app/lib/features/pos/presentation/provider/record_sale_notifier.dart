import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../catalog/presentation/provider/stock_provider.dart';
import '../../domain/model/cart_item.dart';
import '../../domain/model/payment_mode_enum.dart';
import '../../domain/model/sale_model.dart';
import 'cart_provider.dart';
import 'pos_providers.dart';
import 'pos_search_provider.dart';

/// Sealed state for RecordSaleNotifier.
sealed class RecordSaleState {
  const RecordSaleState();
}

class RecordSaleIdle extends RecordSaleState {
  const RecordSaleIdle();
}

class RecordSaleLoading extends RecordSaleState {
  const RecordSaleLoading();
}

class RecordSaleSuccess extends RecordSaleState {
  final Sale sale;
  const RecordSaleSuccess(this.sale);
}

class RecordSaleError extends RecordSaleState {
  final String message;
  const RecordSaleError(this.message);
}

/// RecordSaleNotifier — orchestrates sale submission.
class RecordSaleNotifier extends Notifier<RecordSaleState> {
  @override
  RecordSaleState build() => const RecordSaleIdle();

  Future<void> submit({
    required List<CartItem> cart,
    required PaymentModeEnum mode,
    required String storeId,
    required String employeeId,
    String? clientId,
    String? mobileRef,
  }) async {
    state = const RecordSaleLoading();
    try {
      final cartNotifier = ref.read(cartProvider.notifier);
      final hasDrafts = cartNotifier.hasDraftProducts;
      final status = hasDrafts ? 'PENDING_VALIDATION' : 'COMPLETED';

      // Local stock check — skip for PENDING_VALIDATION sales (no stock decrement)
      if (!hasDrafts) {
        final localDs = ref.read(localSaleDataSourceProvider);
        for (final item in cart) {
          final available = await localDs.getAvailableStock(item.productId, storeId);
          if (available < item.quantity) {
            state = const RecordSaleError('INSUFFICIENT_STOCK');
            return;
          }
        }
      }

      final useCase = ref.read(recordSaleUseCaseProvider);
      final discountAmount = cartNotifier.discountAmount;
      final sale = await useCase.execute(
        cart: cart,
        mode: mode,
        storeId: storeId,
        employeeId: employeeId,
        clientId: clientId,
        mobileRef: mobileRef,
        discountAmount: discountAmount,
        status: status,
      );

      ref.read(cartProvider.notifier).clearCart();
      // Invalidate product/stock providers so POS grid shows updated stock
      ref.invalidate(frequentProductsProvider);
      ref.invalidate(posSearchProvider);
      // Also invalidate the catalog stock providers for price/stock consistency
      ref.invalidate(stockNotifierProvider);
      state = RecordSaleSuccess(sale);
    } catch (e) {
      state = RecordSaleError(e.toString());
    }
  }

  void reset() => state = const RecordSaleIdle();
}

final recordSaleNotifierProvider =
    NotifierProvider<RecordSaleNotifier, RecordSaleState>(
        RecordSaleNotifier.new);
