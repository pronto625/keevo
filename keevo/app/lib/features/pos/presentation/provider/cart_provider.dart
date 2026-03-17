import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../domain/model/cart_item.dart';

/// CartNotifier — manages the POS shopping cart state.
class CartNotifier extends Notifier<List<CartItem>> {
  int _discountAmount = 0;

  @override
  List<CartItem> build() => [];

  void addItem(CartItem item) {
    final idx = state.indexWhere((c) => c.id == item.id);
    if (idx >= 0) {
      final existing = state[idx];
      state = [
        ...state.sublist(0, idx),
        existing.copyWith(quantity: existing.quantity + item.quantity),
        ...state.sublist(idx + 1),
      ];
    } else {
      state = [...state, item];
    }
  }

  void removeItem(String id) {
    state = state.where((c) => c.id != id).toList();
  }

  void updateQuantity(String id, int qty) {
    if (qty <= 0) {
      removeItem(id);
      return;
    }
    state = state.map((c) => c.id == id ? c.copyWith(quantity: qty) : c).toList();
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

  void clearCart() {
    _discountAmount = 0;
    state = [];
  }
}

final cartProvider =
    NotifierProvider<CartNotifier, List<CartItem>>(CartNotifier.new);
