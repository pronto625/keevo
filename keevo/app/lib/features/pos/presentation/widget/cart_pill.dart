import 'package:flutter/material.dart';
import 'package:intl/intl.dart';

/// CartPill — floating pill at bottom of POS showing cart summary.
///
/// Uses [AnimatedSwitcher] to smoothly show/hide based on cart state.
class CartPill extends StatelessWidget {
  final int itemCount;
  final int totalAmount;
  final VoidCallback onEncaisser;

  const CartPill({
    super.key,
    required this.itemCount,
    required this.totalAmount,
    required this.onEncaisser,
  });

  static final _currencyFormat =
      NumberFormat.currency(locale: 'fr_CM', symbol: 'FCFA', decimalDigits: 0);

  @override
  Widget build(BuildContext context) {
    return AnimatedSwitcher(
      duration: const Duration(milliseconds: 200),
      child: itemCount == 0
          ? const SizedBox.shrink(key: ValueKey('empty'))
          : SafeArea(
              key: const ValueKey('pill'),
              child: Padding(
                padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 8),
                child: SizedBox(
                  width: double.infinity,
                  child: FilledButton(
                    onPressed: onEncaisser,
                    style: FilledButton.styleFrom(
                      backgroundColor: const Color(0xFF3B5BDB),
                      padding: const EdgeInsets.symmetric(
                          vertical: 14, horizontal: 20),
                      shape: RoundedRectangleBorder(
                        borderRadius: BorderRadius.circular(28),
                      ),
                    ),
                    child: Text(
                      '$itemCount article${itemCount > 1 ? 's' : ''} · ${_currencyFormat.format(totalAmount)} — Encaisser',
                      style: const TextStyle(
                        fontSize: 15,
                        fontWeight: FontWeight.w600,
                      ),
                    ),
                  ),
                ),
              ),
            ),
    );
  }
}
