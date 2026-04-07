import 'package:flutter/material.dart';
import 'package:intl/intl.dart';

import '../../../../core/theme/app_theme.dart';

/// CartPill — floating pill at bottom of POS showing cart summary.
///
/// Uses [AnimatedSwitcher] to smoothly show/hide based on cart state.
class CartPill extends StatelessWidget {
  final int itemCount;
  final int totalAmount;
  final bool hasDraftProducts;
  final VoidCallback onEncaisser;

  const CartPill({
    super.key,
    required this.itemCount,
    required this.totalAmount,
    this.hasDraftProducts = false,
    required this.onEncaisser,
  });

  static final _currencyFormat =
      NumberFormat.currency(locale: 'fr_FR', symbol: 'XAF', decimalDigits: 0);

  @override
  Widget build(BuildContext context) {
    return AnimatedSwitcher(
      duration: const Duration(milliseconds: 250),
      transitionBuilder: (child, animation) => SlideTransition(
        position: Tween<Offset>(
          begin: const Offset(0, 1),
          end: Offset.zero,
        ).animate(CurvedAnimation(parent: animation, curve: Curves.easeOutCubic)),
        child: child,
      ),
      child: itemCount == 0
          ? const SizedBox.shrink(key: ValueKey('empty'))
          : SafeArea(
              key: const ValueKey('pill'),
              child: Padding(
                padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 8),
                child: Container(
                  width: double.infinity,
                  decoration: BoxDecoration(
                    color: hasDraftProducts
                        ? AppTheme.warning
                        : AppTheme.darkSurface,
                    borderRadius: BorderRadius.circular(16),
                    boxShadow: [
                      BoxShadow(
                        color: AppTheme.darkSurface.withValues(alpha: 0.3),
                        blurRadius: 16,
                        offset: const Offset(0, 6),
                      ),
                    ],
                  ),
                  child: Material(
                    color: Colors.transparent,
                    child: InkWell(
                      onTap: onEncaisser,
                      borderRadius: BorderRadius.circular(16),
                      child: Padding(
                        padding: const EdgeInsets.symmetric(
                            vertical: 12, horizontal: 14),
                        child: Row(
                          children: [
                            // Cart icon with badge
                            Stack(
                              clipBehavior: Clip.none,
                              children: [
                                const Icon(
                                  Icons.shopping_bag_outlined,
                                  color: Colors.white,
                                  size: 24,
                                ),
                                Positioned(
                                  top: -6,
                                  right: -8,
                                  child: Container(
                                    padding: const EdgeInsets.all(4),
                                    decoration: const BoxDecoration(
                                      color: Color(0xFF22C55E),
                                      shape: BoxShape.circle,
                                    ),
                                    child: Text(
                                      '$itemCount',
                                      style: const TextStyle(
                                        color: Colors.white,
                                        fontWeight: FontWeight.w700,
                                        fontSize: 10,
                                        height: 1,
                                      ),
                                    ),
                                  ),
                                ),
                              ],
                            ),
                            const SizedBox(width: 14),
                            // Article count + total
                            Expanded(
                              child: Column(
                                mainAxisSize: MainAxisSize.min,
                                crossAxisAlignment: CrossAxisAlignment.start,
                                children: [
                                  Text(
                                    hasDraftProducts
                                        ? 'BROUILLON'
                                        : '$itemCount ARTICLE${itemCount > 1 ? 'S' : ''}',
                                    style: TextStyle(
                                      color: Colors.white.withValues(alpha: 0.7),
                                      fontWeight: FontWeight.w600,
                                      fontSize: 10,
                                      letterSpacing: 0.8,
                                    ),
                                  ),
                                  const SizedBox(height: 1),
                                  Text(
                                    _currencyFormat.format(totalAmount),
                                    style: const TextStyle(
                                      color: Colors.white,
                                      fontWeight: FontWeight.w700,
                                      fontSize: 15,
                                    ),
                                  ),
                                ],
                              ),
                            ),
                            // Encaisser button
                            Container(
                              padding: const EdgeInsets.symmetric(
                                  horizontal: 18, vertical: 10),
                              decoration: BoxDecoration(
                                color: hasDraftProducts
                                    ? AppTheme.warning
                                    : AppTheme.primary,
                                borderRadius: BorderRadius.circular(12),
                              ),
                              child: Row(
                                mainAxisSize: MainAxisSize.min,
                                children: [
                                  Text(
                                    hasDraftProducts
                                        ? 'BROUILLON'
                                        : 'ENCAISSER',
                                    style: const TextStyle(
                                      color: Colors.white,
                                      fontWeight: FontWeight.w700,
                                      fontSize: 13,
                                      letterSpacing: 0.5,
                                    ),
                                  ),
                                  const SizedBox(width: 6),
                                  const Icon(
                                    Icons.arrow_forward_rounded,
                                    color: Colors.white,
                                    size: 16,
                                  ),
                                ],
                              ),
                            ),
                          ],
                        ),
                      ),
                    ),
                  ),
                ),
              ),
            ),
    );
  }
}
