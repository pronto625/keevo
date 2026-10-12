import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:uuid/uuid.dart';

import '../../../../core/di/providers.dart';
import '../../../../core/sync/sync_gate_guard.dart';
import '../../../catalog/presentation/provider/product_provider.dart';
import '../../../catalog/presentation/provider/stock_provider.dart';
import '../../domain/model/cart_item.dart';
import '../../domain/model/payment_mode_enum.dart';
import '../../domain/model/sale_model.dart';
import '../../domain/model/sale_status.dart';
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
    // v1s-15-8: never record a zero-total sale (cart emptied while the
    // checkout awaited the employee id) — the page CTA is not the only path.
    if (cart.isEmpty) {
      state = const RecordSaleError('EMPTY_CART');
      return;
    }
    state = const RecordSaleLoading();
    try {
      final cartNotifier = ref.read(cartProvider.notifier);

      // Collect draft items and their initial stocks BEFORE any mutations.
      final draftItems = cart.where((c) => c.isDraft).toList();
      // v1s-13-8: only entries for draft products still in THIS cart — a stale
      // entry (product removed from the cart, or no longer a draft) must be
      // neither applied locally nor queued.
      final draftProductIds = draftItems.map((c) => c.productId).toSet();
      final initialStockEntries = <String, int>{
        for (final e in ref.read(draftInitialStocksProvider).entries)
          if (draftProductIds.contains(e.key)) e.key: e.value,
      };

      // Local stock check for ALL items BEFORE any local mutation: a draft
      // product's initial stock entry counts toward what is available, so a
      // refused sale leaves stock, product status and the entered initial
      // stocks untouched (a retry would otherwise credit the entry twice).
      final localDs = ref.read(localSaleDataSourceProvider);
      for (final item in cart) {
        final available =
            await localDs.getAvailableStock(item.productId, storeId);
        final initialEntry =
            item.isDraft ? (initialStockEntries[item.productId] ?? 0) : 0;
        if (available + initialEntry < item.quantity) {
          state = const RecordSaleError('INSUFFICIENT_STOCK');
          return;
        }
      }

      final originalDraftProductIds =
          draftItems.map((c) => c.productId).toList();
      // v1s-12-10: draft promotion is OWNER-only. An EMPLOYEE sale still
      // records the initial stock and notifies the owner, who validates the
      // drafts from the catalog. Fail closed: an unknown (null) role does not
      // promote either — the backend rejects non-owner promotion anyway.
      final promoteDraftProducts =
          ref.read(currentUserRoleProvider) == 'OWNER';
      final useCase = ref.read(recordSaleUseCaseProvider);
      final discountAmount = cartNotifier.discountAmount;
      final movementIds = {
        for (final productId in initialStockEntries.keys)
          productId: const Uuid().v4(),
      };

      Future<Sale> record() => useCase.execute(
            cart: cart,
            mode: mode,
            storeId: storeId,
            employeeId: employeeId,
            clientId: clientId,
            mobileRef: mobileRef,
            discountAmount: discountAmount,
            status: SaleStatus.completed.value,
            originalDraftProductIds: originalDraftProductIds,
            initialStockEntries: initialStockEntries,
            initialStockMovementIds: movementIds,
            promoteDraftProducts: promoteDraftProducts,
          );

      final Sale sale;
      if (draftItems.isEmpty) {
        sale = await record();
      } else {
        // Initial stock + promotion (OWNER only) + sale (and its queued sync ops) commit
        // together: if the sale fails, the entries are not credited locally.
        final localProductDs = ref.read(localProductDataSourceProvider);
        sale = await ref.read(appDatabaseProvider).transaction(() async {
          await localDs.applyInitialStockEntries(
              initialStockEntries, storeId, employeeId,
              movementIds: movementIds);
          if (promoteDraftProducts) {
            for (final item in draftItems) {
              await localProductDs.promoteToActive(item.productId);
            }
          }
          return record();
        });
      }
      // Cleared after every successful sale (stale entries included).
      ref.read(draftInitialStocksProvider.notifier).state = {};

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
