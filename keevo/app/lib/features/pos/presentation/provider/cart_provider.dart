import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../domain/model/cart_item.dart';

/// CartNotifier — manages the POS shopping cart state.
class CartNotifier extends Notifier<List<CartItem>> {
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

  void clearCart() => state = [];

  int get totalAmount => state.fold(0, (sum, c) => sum + c.subtotal);
}

final cartProvider =
    NotifierProvider<CartNotifier, List<CartItem>>(CartNotifier.new);
