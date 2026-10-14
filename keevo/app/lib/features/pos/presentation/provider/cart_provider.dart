import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../domain/model/cart_item.dart';

/// CartNotifier — manages the POS shopping cart state.
class CartNotifier extends Notifier<List<CartItem>> {
  int _discountAmount = 0;

  @override
  List<CartItem> build() => [];

  // Every cart edit goes through here, so the discount is re-bounded before
  // listeners rebuild: a discount above the cart total would record a sale
  // with a negative total, which the backend rejects on every sync.
  @override
  set state(List<CartItem> value) {
    final total = value.fold(0, (sum, c) => sum + c.subtotal);
    if (_discountAmount > total) _discountAmount = total;
    super.state = value;
  }

  /// Adds [item] (or its quantity to the same line). Returns false, and
  /// changes nothing, when the line would go above its known stock.
  bool addItem(CartItem item) {
    final idx = state.indexWhere((c) => c.id == item.id);
    if (idx >= 0) {
      final existing = state[idx];
      final qty = existing.quantity + item.quantity;
      if (!existing.allows(qty)) return false;
      state = [
        ...state.sublist(0, idx),
        existing.copyWith(quantity: qty),
        ...state.sublist(idx + 1),
      ];
    } else {
      if (!item.allows(item.quantity)) return false;
      state = [...state, item];
    }
    return true;
  }

  void removeItem(String id) {
    state = state.where((c) => c.id != id).toList();
  }

  /// Returns false, and changes nothing, when [qty] is above the known stock.
  bool updateQuantity(String id, int qty) {
    if (qty <= 0) {
      removeItem(id);
      return true;
    }
    if (!_fitsStock(id, qty)) return false;
    state = state.map((c) => c.id == id ? c.copyWith(quantity: qty) : c).toList();
    return true;
  }

  /// Sets quantity directly to [qty] — used by the POS quantity dialog (AC6, Story 7.6).
  /// Ignores invalid (≤ 0) input to avoid accidental deletion.
  /// Returns false, and changes nothing, when [qty] is above the known stock.
  bool setQuantity(String itemId, int qty) {
    if (qty <= 0) return true;
    if (!_fitsStock(itemId, qty)) return false;
    state = state
        .map((c) => c.id == itemId ? c.copyWith(quantity: qty) : c)
        .toList();
    return true;
  }

  bool _fitsStock(String id, int qty) {
    for (final c in state) {
      if (c.id == id) return c.allows(qty);
    }
    return true;
  }

  void updatePrice(String id, int newPrice) {
    state = state.map((c) => c.id == id ? c.copyWith(appliedUnitPrice: newPrice) : c).toList();
  }

  bool setDiscount(int amount) {
    if (amount > totalAmount) return false;
    _discountAmount = amount.clamp(0, totalAmount);
    ref.notifyListeners();
    return true;
  }

  int get discountAmount => _discountAmount;

  int get totalAmount => state.fold(0, (sum, c) => sum + c.subtotal);

  int get finalTotal => (totalAmount - _discountAmount).clamp(0, totalAmount);

  bool get hasDraftProducts => state.any((c) => c.isDraft);

  List<String> get draftProductIds =>
      state.where((c) => c.isDraft).map((c) => c.productId).toList();

  void clearCart() {
    _discountAmount = 0;
    state = [];
  }
}

final cartProvider =
    NotifierProvider<CartNotifier, List<CartItem>>(CartNotifier.new);

/// Holds initial stock quantities for originally-draft products during checkout.
/// Set by CartBottomSheet after the user enters initial stocks via dialogs,
/// then read and cleared by RecordSaleNotifier.submit().
final draftInitialStocksProvider =
    StateProvider<Map<String, int>>((ref) => {});
