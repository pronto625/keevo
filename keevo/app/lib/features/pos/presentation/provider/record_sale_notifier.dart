import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../../core/sync/sync_gate_guard.dart';
import '../../../catalog/presentation/provider/product_provider.dart';
import '../../../catalog/presentation/provider/stock_provider.dart';
import '../../domain/model/cart_item.dart';
import '../../domain/model/payment_mode_enum.dart';
import '../../domain/model/sale_model.dart';
import 'cart_provider.dart';
import 'day_closure_providers.dart';
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

class RecordSaleBlockedByGate extends RecordSaleState {
  const RecordSaleBlockedByGate();
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
    try {
      SyncGateGuard.assertWriteAllowed(ref);
    } on WriteBlockedException {
      state = const RecordSaleBlockedByGate();
      return;
    }
    state = const RecordSaleLoading();
    try {
      final cartNotifier = ref.read(cartProvider.notifier);

      // Collect draft items and their initial stocks BEFORE any mutations.
      final draftItems = cart.where((c) => c.isDraft).toList();
      final initialStockEntries =
          Map<String, int>.from(ref.read(draftInitialStocksProvider));

      // For draft products: apply initial stock locally + activate locally.
      if (draftItems.isNotEmpty) {
        final localSaleDs = ref.read(localSaleDataSourceProvider);
        final localProductDs = ref.read(localProductDataSourceProvider);

        await localSaleDs.applyInitialStockEntries(
            initialStockEntries, storeId, employeeId);

        for (final item in draftItems) {
          await localProductDs.promoteToActive(item.productId);
        }

        // Clear the collected initial stocks.
        ref.read(draftInitialStocksProvider.notifier).state = {};
      }

      // Local stock check for ALL items (draft products now have stock applied).
      final localDs = ref.read(localSaleDataSourceProvider);
      for (final item in cart) {
        final available =
            await localDs.getAvailableStock(item.productId, storeId);
        if (available < item.quantity) {
          state = const RecordSaleError('INSUFFICIENT_STOCK');
          return;
        }
      }

      final originalDraftProductIds =
          draftItems.map((c) => c.productId).toList();
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
        status: 'COMPLETED',
        originalDraftProductIds: originalDraftProductIds,
        initialStockEntries: initialStockEntries,
      );

      ref.read(cartProvider.notifier).clearCart();
      ref.invalidate(frequentProductsProvider);
      ref.invalidate(posSearchProvider);
      ref.invalidate(stockNotifierProvider);
      ref.invalidate(todaySummaryProvider);
      ref.invalidate(dayClosureStateProvider);
      if (draftItems.isNotEmpty) {
        ref.invalidate(pendingDraftsCountProvider);
      }
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
